# Architecture

The Stats App receives statistics records from Heimdal Core, buffers them through
RabbitMQ, stores them per stat type in PostgreSQL, and serves them back through a
read-only, tenant-scoped REST API. This document is the design record: what the system
does, the invariants it keeps, and why. Contracts live in [contracts/](contracts/), the
schemas in [schemas/](schemas/), procedures in [runbooks/](runbooks/).

```text
Heimdal Core
   │  POST /internal/stats  (and /external/stats for other callers)
   ▼
stats-input ── validate the envelope ── publish, wait for the broker's confirm ── 2xx
   │                                                                             (503 otherwise)
   ▼
RabbitMQ   exchange app_stats_input (topic), routing key = stat type
   │        app_statsstore.<type>          main queue, one per type
   │        app_statsstore.<type>.retry.N  three retry tiers (30 s, 5 min, 30 min)
   │        app_statsstore.<type>.dlq      dead letters, never expire
   ▼
stats-writer ── validate the payload ── INSERT … ON CONFLICT DO NOTHING ── ACK after commit
   │             (one deployment per stat type, N replicas each)
   ▼
PostgreSQL 17  one table per type, daily partitions on ts_device, 90-day retention
   ▲
stats-api ── Keycloak bearer token ── tenant from the token ── GET only
```

RabbitMQ, PostgreSQL and Keycloak are infrastructure, not deployables. Java 21, Spring
Boot 3.5.16, Gradle wrapper, Flyway, Testcontainers.

## 1. Modules

| Module | Responsibility | Database | Broker |
|---|---|---|---|
| `stats-input` | HTTP ingest. Validates the envelope, publishes the received bytes unchanged, answers 2xx only after a publisher confirm. Stateless. | **none** | declares the exchange |
| `stats-writer` | Consumes one stat type's queue, validates the payload, writes PostgreSQL, owns retries, dead letters, partitions and retention. **The only writer.** | `stats` (read/write, DDL) | declares queues and bindings |
| `stats-api` | Read-only REST. Validates Keycloak tokens, reads with a tenant predicate. | `stats_ro` (SELECT only) | **none** |
| `stats-common` | The envelope model, the stat-type enum, the AMQP topology definitions, RFC 9457 errors, and schema validation. No module-specific logic. | — | — |
| `stats-testsupport` | Shared Testcontainers infrastructure and the declaration-conflict assertion. Test scope only. | — | — |

Application modules depend on `stats-common`, never on each other. Three images, one per
application module. The Writer image is deployed **once per stat type** (`STAT_TYPE`), so
prefetch, concurrency and replica count are tuned per type and one type's crash loop
cannot stop the other four. `STAT_TYPE=all` is a development convenience and is refused
under the `prod` profile.

Database roles make "only the Writer writes" a database error rather than a review
finding: `stats_ro` has `SELECT` only, and Input has no database credentials at all.

## 2. The record

Field names are the **production wire format**, taken from real records. One name per
thing on the wire, in the schema, in Java, in the column and in the API — there is no
rename layer, because a rename layer next to `tenant`/`maintenant` is exactly where a
field gets silently swapped.

```json
{
  "uid": "CLSCON-11d3ec4c-f6e1-403a-a9ef-b6f5fe909eee",
  "tenant": "mzc", "maintenant": "mzc",
  "clsbox": "04B64810652B",
  "type": "lorastats", "version": 1,
  "ts": { "server": 1787833739, "device": 1787833739,
          "datastore": 1787833739, "converter": 1787833739,
          "expiresOn": "2026-08-27T12:28:59.074Z" },
  "data": { },
  "status": ["conversion ok"]
}
```

| Field | Meaning |
|---|---|
| `uid` | Record identifier, unique per Heimdal. Up to 128 characters. Also the log correlation ID. |
| `tenant` | The tenant the gateway belongs to. **The isolation field.** |
| `maintenant` | The maintaining tenant. Moves with `tenant` and equals it on every tenant except minol. **Never** used for isolation. |
| `clsbox` | The device identifier, 12 hex characters (the gateway's MAC). Stored uppercase. "Gateway" is the physical device in prose, never a field. |
| `ts.server` | Heimdal's receipt clock — the arrival clock. Unix **seconds**. |
| `ts.device` | When the gateway measured the record. Unix **seconds**. **Retention keys on this.** |
| `ts.datastore`, `ts.converter`, `ts.expiresOn`, `ts.deviceOrig`, `meta`, `status`, `version` | **Passthrough**: accepted, stored verbatim in `passthrough jsonb`, returned, never branched on. `ts.converter` presence and a `status` other than exactly `["conversion ok"]` are metered and nothing more. `ts.expiresOn` is a string or `{"$date": …}`. |
| `data` | The per-type payload, validated by the Writer. |

**No timestamp is normalised, ever.** The published message is byte-identical to the
received body. That is what makes duplicate suppression correct: a redelivered or replayed
record carries the same `ts_device`, lands in the same partition, and collides on the same
key. Any proposal to transform a record in flight has to answer for that first.

**Unknown fields** are a decision, not a deserialiser default: unknown envelope and `data`
members are accepted and preserved (`passthrough`, `data_extra`); invalid known fields are
rejected.

**`version` is not a dialect discriminator.** Production `lorastats` arrives in three
incompatible payload dialects — bare names with integers, bare names with strings, and
`lora_`-prefixed names — and all three carry `version: 1`. The schema is a closed `oneOf` of
exactly those three; the Writer detects the dialect structurally and normalises all three to
one row. An unknown fourth dialect dead-letters, and a DLQ spike is never fixed by widening
the schema to make it stop. `VersionIsNotADiscriminatorTest` guards the code.

When a device's state legitimately removes a field — a `netstats` interface that never came
up has no address, a 2G modem reports no `rsrp`/`rsrq`, an unregistered modem sends only
`{"etxt": "unknown technology"}` — the field is optional or a second closed shape exists.
Before making a field required, ask what the record looks like when the thing it measures is
failing: those are the records operations most needs.

## 3. Ingest

**Two gates, two consequences.**

| Gate | Schema | Where | Failure |
|---|---|---|---|
| Envelope | `envelope-v1.schema.json` | `stats-input` | **400**, nothing published |
| Payload | the per-type schema | `stats-writer` | **dead letter**, after a confirmed hand-off |

Heimdal retries only on a non-2xx, so a payload problem answered with 400 is unrecoverable
data loss, while a dead letter can be fixed and redriven. Never move payload validation
into Input. Input does refuse what PostgreSQL could never store whatever we fixed: an
identifier over 128 characters or with no visible character, `version` over 32767, U+0000 or
an unpaired surrogate, invalid UTF-8, a duplicated member or trailing content (the body is
parsed as bytes, strictly), and a `ts.device` more than `max-clock-skew` (5 min) ahead of
`ts.server` or a `ts.server` more than `max-server-clock-ahead` (1 h) ahead of Input's clock.
The clock checks are cross-field, which JSON Schema cannot express, so the fixtures stay
schema-valid and carry `$expect: "input-reject"`.

**A 2xx is a promise.** Input publishes `persistent`, with `mandatory=true` and a
`ReturnsCallback`, and answers only after the broker's confirm:

* confirmed → 200 with `{"status":"accepted","traceId":…}`;
* nack, confirm timeout or broker down → 503 with `Retry-After`, nothing buffered locally,
  Heimdal retries;
* returned as unroutable → 503 and `records_unroutable`. A confirm says the broker accepted
  the message, not that any queue wanted it; without `mandatory` a missing binding would be
  acknowledged and the record dropped with every component reporting success.

**Two doors.** `/internal/stats` is Heimdal's; `/external/stats` serves other callers. Both
run the same handler, gate, limits and authentication filter; every Input metric carries
`source="internal"|"external"`. The external door does **not** bind the record's `tenant`
to the caller, so anything that can reach it can write under any tenant's name. Keep it off
shared networks until a token-tenant check exists (see §9).

**Bounds.** 256 KB per request (413) and 4,000 requests/s per instance (429 with
`Retry-After`). These and the 503 backpressure are the only replay controls the Stats App
owns: Heimdal has agreed to cap replay at 2× nominal (2,667/s), and an agreement is not a
control.

## 4. Messaging

| Object | Shape |
|---|---|
| Exchange | `app_stats_input`, topic, durable; routing key is the stat type |
| Main queue | `app_statsstore.<type>`, quorum, durable |
| Retry tiers | `app_statsstore.<type>.retry.1-3`, quorum; TTL 30 s / 5 min / 30 min from configuration, dead-lettering back through `app_stats_dlx` |
| DLQ | `app_statsstore.<type>.dlq`, quorum, **no TTL and no onward route** |

One queue per type, because a shared queue lets one type's poison message stall the others
and prevents per-type scaling. Quorum queues because RabbitMQ 4 has no mirrored classic
queues, and a queue's type is fixed at declaration.

Only the broker-connected modules declare topology, and both build every declaration from
`AmqpTopology`. Two modules declaring the same queue with different arguments is a
`RESOURCE_LOCKED` startup failure, so `AmqpDeclarationAssert` compares both modules'
declarations against the shared definitions in the build. The API has no broker
dependency at all, which `NoBrokerConnectionTest` asserts on the classpath.

**The DLQ never expires anything.** A message there has survived three retry tiers and is the
only remaining copy of a record Heimdal believes it delivered; a TTL would delete it silently.
`deadLetterQueueArguments()` has no parameter that could add one. Broker disk is bounded by
alerting (depth above zero; non-empty for 7 and 12 days) and manual disposition within 14
days. Redrive is an authorised operator action, per type, capped at 2,667/s, after the root
cause is fixed — `docs/runbooks/dlq-redrive.md`. A record redriven after ~90 days is past
retention and is skipped, not recovered.

## 5. Writer

Acknowledgement is **manual and follows the PostgreSQL commit**. On failure the Writer
publishes the record to the next retry tier, or to the DLQ, **waits for that publish's
confirm, and only then ACKs the original**. The reverse order opens a window where the
original is gone and the copy does not exist; a crash there loses the record silently. The
chosen order turns a crash into a duplicate, which the database absorbs.

If the hand-off is not confirmed (a deleted DLQ, a broker resource alarm), the original is
nacked **with requeue after a pause** (`unconfirmed-handoff-requeue-delay`, 5 s) and
`records_handoff_unconfirmed` moves. Rethrowing would leave a manual-mode delivery
unacknowledged on the channel forever, one prefetch slot lost each time. Immediate requeue is
never used for a persistent failure: it is a hot loop.

Failures are classified. Permanent — a payload-gate rejection, a parse failure, a
range-check failure, SQLSTATE classes `22` and `54` — skip the retry tiers; nothing about
them becomes true by waiting. Anything unrecognised is retried. The attempt count rides in
the Writer's own `x-stats-attempt` header, because `x-death` resets on an explicit publish.

**Idempotency.** `INSERT … ON CONFLICT (ts_device, uid) DO NOTHING`, with the affected-row
count read and a suppressed insert metered as `records_duplicate_suppressed`. During a replay
that counter spikes, and that is the system working. `tenant` is not in the key; the counter
is what would reveal a cross-tenant `uid` collision.

**Writes** are individual inserts behind a batch-shaped interface, so a poison record is
isolated precisely and micro-batching remains a tuning change. Each type parses its own wire
shape (the same concept can be an integer in one type and a string in another), and every
conversion to `bigint` goes through `WireNumbers`, which range-checks **before** converting:
a 19-digit string fits the schema's pattern and overflows `bigint`, and an unchecked overflow
corrupts the value with no error. Unmapped `data` members land verbatim in `data_extra` and
are metered.

## 6. Storage and retention

| Decision | Value | Why |
|---|---|---|
| Partitioning | `PARTITION BY RANGE (ts_device)`, daily | Retention becomes a catalogue operation; time-ranged reads prune |
| Columns | Typed for `lorastats`, `ltestats`, `sysstats`, `omsstats`; `netstats` keeps typed envelope columns and a `jsonb` interface map | `jsonb` repeats key names in every row (hundreds of GB per type); `netstats` interfaces are open-ended by design |
| Key | `PRIMARY KEY (ts_device, uid)` | A unique index on a partitioned table must include the partition key, so uniqueness is per partition; a redelivery carries the same `ts_device` and still collides |
| Indexes | `(tenant, clsbox, ts_device DESC, uid DESC)` and `(tenant, uid)` | The dominant tenant/device/time query with a unique tie-breaker; the `uid` lookup that cannot prune |
| `clsbox` | Uppercased by the Writer, `CHECK (clsbox ~ '^[0-9A-F]{12}$')` | One device in two letter cases would be two index keys and a half-empty page. The envelope still accepts either case; the API filters with `clsbox = upper(:clsbox)` so the index is used |
| Extra columns | `passthrough jsonb`, `data_extra jsonb`, `record_version`, `schema_version NOT NULL` | Lossless storage; `schema_version` records which of our schemas admitted the row |
| Migrations | Flyway, versioned, owned by the Writer | Plain SQL suits partition DDL |

Volume is 160,000 gateways reporting every ten minutes: 23 million rows per type per day,
about two billion live rows per type at 90 days. Retention is therefore not a cleanup job but
a second write path at full ingest rate, which is why it is **never row deletion**.

**Retention** keys on `ts.device`, 90 days by default (`stats.retention.default-days`, no
default in code, per-type overrides validated against the stat types). Once a day, per type,
behind `pg_try_advisory_lock(hashtext('stats.retention:' || type))` because every type runs
several replicas, the Writer:

1. finalises any partition left *pending detach* by an interrupted run (`DETACH … FINALIZE`) —
   otherwise every later detach on that table fails while inserts keep working and nothing
   looks wrong;
2. pre-creates every partition in `[cutoffDay, today + pre-create-days]` — the whole window,
   not only ahead, so a gateway back after weeks offline always finds its day;
3. detaches (`CONCURRENTLY`, which does not block the writer) and drops each partition wholly
   past the cutoff, and drops any table orphaned between a detach and a drop.

Pre-creation also runs at startup; a Writer that cannot create its partitions refuses to
start, because consuming into a table with no partition fails every insert. The types are
staggered rather than fired together.

**Late arrival.** A record whose `ts.device` is already past the window is skipped, metered as
`records_expired_on_arrival`, logged at `WARN` and **acknowledged** — never dead-lettered,
because it is valid and a redrive would re-derive the same verdict forever. The guard and the
sweep read **one** cutoff (`RetentionWindow`), so a record can never be admitted into a
partition that was just dropped. `StatsAppLateArrival` alerts on any increase.

**Backup.** Restore is per daily partition (`docs/runbooks/partition-restore.md`). Signed
targets: RPO ≤ 5 min through continuous WAL archiving, RTO 1 h per partition and 24 h for a
full cluster restore. `postgres-alerts.yml` pages when archiving fails, stalls or is off.

## 7. Read API and tenant isolation

`GET /v1/stats/{type}?from&to`, `GET /v1/stats/{type}/{clsbox}/{from}[/{to}]` and
`GET /v1/stats/{type}/{uid}`; the contract is [contracts/openapi.yaml](contracts/openapi.yaml).

* The tenant comes **only** from the validated token's `tenant` claim, a single-valued string.
  A multi-valued claim would turn the predicate into `tenant IN (…)` and let one misconfigured
  user read several tenants with every test green. A caller cannot override it by parameter,
  header, path or body.
* The tenant predicate is in the **SQL** of every query, count and cursor, and the tests check
  the generated SQL, not only the response.
* A missing **or empty** `tenant` claim is 403; absent never means "all tenants". A token
  without `stats.readOnly` is 403; no valid token is 401. A token that cannot be checked
  because Keycloak's signing keys are unreachable is 503 with `Retry-After`, never a 401 that
  would tell a caller with a good token to fix it. Issuer and audience are validated,
  and a blank issuer or audience stops the API at startup, because an empty audience list
  would switch the audience check off.
* A `uid` in another tenant is **404**, identical to one that does not exist. A `clsbox` the
  tenant does not own is an **empty 200**, identical to an unknown device — on a list a 404
  would say "this gateway exists elsewhere".
* A row's `tenant` is the tenant at measurement time. When a gateway moves tenant, its earlier
  rows stay with the old tenant; nothing re-labels history.
* Only GET. Every other method on `/v1/**` is 405 with `Allow: GET` and no side effect.
* `ltestats.imsi` is stored as received and returned **masked** on every route (carrier
  prefix and at most the last four digits visible). Masking is not configurable.
* Pagination is keyset on `(ts_device DESC, uid DESC)` with an HMAC-signed cursor bound to the
  walk and the tenant; default page 100, maximum 1000. The tenant-wide list is limited to a
  1-hour window (provisional). Queries run under a 10 s `statement_timeout`.

**Row-level security is deliberately not used.** `SET LOCAL app.tenant_id` binds to a
transaction on a pooled connection; one read path that forgets it inherits whatever the
previous transaction left, and that failure is silent and fails open. The mandatory query
predicate, checked against generated SQL, is the control. Revisit if a second service gains
direct read access to these tables, or an audit requires isolation provable in the database.

## 8. Observability

Every module exposes `/actuator/health/liveness` (the process only) and
`/actuator/health/readiness` (its real dependencies: Input — broker; Writer — database and
broker; API — database and Keycloak's signing keys). Readiness is configured explicitly
because Boot's default reports UP with every dependency down.

Logs are structured ECS JSON to stdout with `traceId`, `uid`, `statType`, `tenant`, `clsbox`,
`module` and `event`; `CONTRIBUTING.md` has the logging rules. Metrics are Prometheus via
Micrometer: the Input and Writer metric contracts are
[contracts/heimdal-input-contract-v1.md](contracts/heimdal-input-contract-v1.md) and
[contracts/writer-metrics-v1.md](contracts/writer-metrics-v1.md); alert rules and their
`promtool` tests are in `ops/prometheus/`, dashboards in `ops/grafana/`. Broker gauges are
cached and read on a schedule, never on scrape; a failed poll writes `NaN`, and an alert says
so rather than letting the queue rules go quiet. Queue health is alerted on the **age** of the
oldest message, not depth.

## 9. Inbound authentication

Heimdal's mechanism (OAuth2 client credentials or mTLS) has not been agreed with ZC, so
authentication is a configuration-selected filter on both ingest paths —
`stats.input.auth.mode` = `none | oauth2 | mtls`, with **no default in code** — and ZC's
answer is a configuration change.

* **`oauth2`** (implemented, our proposal): a bearer token from the `stats-input` Keycloak
  client's client-credentials grant. Signature, `iss` (compared as a string), `aud`
  (`stats-input`) and lifetime are validated by Spring Security's JWT decoder, and the realm
  role `stats.ingest` is required. No token or a bad one is 401, a token without the role is
  403, and unreachable signing keys are 503 so Heimdal retries. A blank issuer or audience
  stops the application at startup. The filter covers the two ingest paths only, never
  actuator: Spring Security's web filter chain is deliberately not used, so the probes keep
  their posture.
* **`none`**: logs a `WARN` on every startup and is refused under the `prod` profile. Anything
  that can reach the Input port can write statistics — acceptable on a development stack only,
  and what the local stack and bundle run.
* **`mtls`**: not implemented; refuses to start rather than serving an endpoint that claims to
  be authenticated.

No mode yet binds the record's `tenant` to the caller on the external door (§3).

## 10. Pitfalls

* **`tenant` and `maintenant` are equal on almost every real record.** A filter on the wrong
  field is invisible to production data; only fixtures where they differ can catch it, which
  is why every fixture differs. On minol they differ, and the wrong filter silently drops rows.
* **Seconds, not milliseconds.** A millisecond timestamp read as seconds lands tens of
  thousands of years ahead; the envelope bounds timestamps to 2000–2100.
* **Pagination without a unique tie-breaker** repeats or skips rows. Always
  `(ts_device DESC, uid DESC)`.
* **A required field that is absent because a device is failing** dead-letters the records
  operations most needs.
* **Normalise before mapping.** `data_extra` is the residue of the normalised payload; taken
  from the wire shape, every prefixed-dialect record would leave its typed columns null.
* **A property set to nothing is not an unset property.** Refuse blank as well as absent.
* **The Writer's daily cron does not fire while a laptop sleeps.** On a development stack,
  `retention_last_sweep_timestamp_seconds` says when the last sweep ran (`NaN`: never).

## 11. Open external decisions

These belong to ZC or other owners. Each is a configuration or schema change when answered,
not a redesign.

| Topic | State |
|---|---|
| Heimdal's credential (OAuth2 or mTLS; issuer, audience, client, rotation) | Open; our proposal, OAuth2 client credentials, is implemented as `stats.input.auth.mode=oauth2` |
| Whether the push is a Heimdal ForwardRule (credentials in the URL, bounded `maxRetry`) | Open |
| Ingest paths and success code (200 or 202) | Engineering defaults, awaiting confirmation |
| Keycloak realm, client, audience, role and claim names | Proposed and live in the development realm, awaiting confirmation |
| `ts.expiresOn` spelling on the HTTP forward; when Heimdal rewrites `ts.device` | Open; both are accepted and preserved |
| Where `netstats` records come from (none exist in the live datastore) | Open; schema, queue and table are ready |
| IPv6 on `netstats` interfaces | Not accepted until a gateway is known to send it |
| Whether old `lorastats` dialects are being retired | Open; `records_lorastats_dialect` shows the mix |
| Data handling: classification, log retention and access, audit, encryption at rest | Open — `decisions/DATA-SENSITIVITY-INVENTORY.md` |
| Tenant-wide query window (1 h) | Provisional pending consumer confirmation and load testing |
