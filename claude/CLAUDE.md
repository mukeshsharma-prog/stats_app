# CLAUDE.md

Project context for AI-assisted development on the **Stats App**.
This file lives in `claude/` together with every other working document.
It is still auto-loaded every session: the repository-root `CLAUDE.md` is a
pointer file whose only content is an `@claude/CLAUDE.md` import. Edit this file,
not the pointer.

> **Version 1.7 — 29 August 2026.** Rebuilds the envelope on the **production wire
> format** after Hasan supplied 15 real records. Governing decision: **ADR-007**.
>
> **Changed in 1.7 — this is the largest change since MongoDB was dropped. Re-read
> all of it:**
>
> * **The device field is `clsbox`, not `gateway`.** The arrival clock is
>   **`ts.server`**, not `ts.processor`. Production has never used the SRS names.
>   One vocabulary, end to end — wire, schema, Java, PostgreSQL column, API
>   response. There is deliberately **no rename layer** (ADR-007 §1).
> * **`ts.converter` is now VALID.** It is on 100% of production records, and the
>   rule that rejected it rejected every record Heimdal sends. ADR-004's clause 3 is
>   **withdrawn**; ADR-004's actual decision — no timestamp normalisation — is
>   unchanged (ADR-007 §2).
> * **`ts.datastore`, `ts.expiresOn`, `version` and `status` are real envelope
>   members.** Accepted, preserved, never read. `ts.expiresOn` is a **string**, not
>   epoch seconds.
> * **There are now TWO validation gates.** Input validates the **envelope only** and
>   a failure is a 400. The Writer validates the **payload** and a failure is a
>   **dead-letter**. "Invalid" no longer implies "400" (ADR-007 §5).
> * **`lorastats` arrives in three incompatible dialects** and `version` does not
>   discriminate them. We encode one and dead-letter the rest. This is an assumption
>   pending ZC (Q15a) and it dead-letters ~2/3 of lorastats.
> * **`ltestats` numerics are STRINGS** on the wire, and the field is
>   **`access_Technology`** with a capital T.
> * **`netstats` interfaces require only `tx`/`rx`.** A down modem sends no address.
>
> **Version 1.6 — 29 August 2026.** Adds the six decisions of 29 Aug 2026 (§7,
> "Decided 29 August 2026") and closes Q14a, Q3a and Q5a. Full reasoning:
> `claude/OPEN_QUESTIONS_STATUS_29AUG2026.md`.
>
> **Changed in 1.6 — re-read these:**
>
> * **Q14a is closed.** A `ts.device` more than `stats.input.validation.max-clock-skew`
>   (default 5 min) ahead of `ts.server` is **rejected at Input** with 400 and the
>   field code `clock_skew_exceeded` (ADR-003 D9). It was never a product question.
> * **Q3a is closed.** Only **broker-connected** modules declare topology. `stats-api`
>   gets no RabbitMQ connection (ADR-002 §11).
> * **Q5a is closed at 115.2M records/day.** The internal "around 10 million" is
>   withdrawn. It never blocked freezing the schema.
> * **The DLQ has no TTL, ever** (ADR-002 §12). Alert on depth > 0 and oldest-message
>   age; disposition is manual.
> * **`pg_partman` is not a dependency.** Partition pre-creation is a scheduled task in
>   `stats-writer` behind the D3 advisory lock (ADR-003 D1).
> * **`stats-common` holds five things, not four** — the Draft 2020-12 schema validator
>   is one of them (D15). §2 now says so.
> * **A token that authenticates but has no usable `tenant` claim is 403, not 401.**
>   `openapi.yaml` said 401; ADR-006 wins and the contract is corrected.
>
> **Version 1.5 — 24 August 2026.** Incorporates the answers in
> `Stats_App_Answers_v1_0.docx`, the v1.2 amendment set, the six gate decisions
> (G1–G6), and a full cross-document reconciliation. The most significant change
> from v1.0: **the database is PostgreSQL, not MongoDB.** If you are working from a
> cached memory of this file that says MongoDB, discard it.
>
> **Changed in 1.5 — if you cached anything earlier, re-read these:**
>
> * **`uid` uniqueness is `PRIMARY KEY (ts_device, uid)` per partition** (rule 13,
>   G2, ADR-003). Nine documents still said "unique constraint on `uid`", which
>   describes an object PostgreSQL cannot create on a partitioned table. All fixed.
> * **The read role is `stats.readOnly`** — not `readOnly`, not `stats-reader`. The
>   property is `stats.security.read-role`. Four spellings were in circulation for
>   a security control.
> * **Q1a is closed: there is no internal reference repo.** Spring Boot 3.5.16 and
>   Flyway are pinned, not provisional. Ten documents plus the version catalog said
>   otherwise.
> * **The unknown-field policy is now `D14`, not `D6`.** Answer-set `D6` means
>   byte-identical publication and ADR-003's `D6` is the migration tool — see the
>   decision-ID namespace table in §8.
> * **ADR-006 is ACCEPTED**, not pending. The ARD is **v1.1**. `netstats` is strict.
>   Retry is three fixed tiers, never a backoff multiplier. Phases run 0–5 and the
>   API is Phase 4.
>
> **Changed in 1.4 — the whole of §7 moved.** All six §2b items were answered on
> 18 Aug 2026: there is no internal reference repo, `netstats` validation is strict,
> Heimdal auth is deferred behind a config switch, the Keycloak realm is proposed and
> live, capacity assumes a 10× synchronised burst, and the fixture set is built. The
> reasoning is in `docs/decisions/2B-ANSWERS-18AUG2026.md`.

---

## 1. What this project is

The **Stats App** is an add-on to the **Datahub Core** at Zenner. It receives
statistics data pushed from the **Heimdal Core**, buffers it through RabbitMQ,
persists it per statistics type in PostgreSQL, and exposes it back out through a
read-only REST API.

It is a **multi-module Gradle project**: three application modules plus a shared
module, producing **three Docker images**.

```
Heimdal Core
     │  HTTP POST
     ▼
┌─────────────────┐   AMQP publish    ┌──────────────┐   AMQP consume   ┌─────────────────┐
│  Module 1       │ ────────────────► │  RabbitMQ    │ ───────────────► │  Module 2       │
│  Input Module   │  exchange:        │              │  one queue       │  Stats Writer   │
│  (validate)     │  app_stats_input  │              │  PER STAT TYPE:  │  (persist)      │
│                 │  (TopicExchange)  │              │  app_statsstore. │                 │
│  200 only after │  routing key =    └──────┬───────┘  <type>          └────────┬────────┘
│  publisher      │  stat type               │                                   │
│  confirm        │                          ▼                                   ▼
└─────────────────┘                 ┌──────────────────┐                ┌─────────────────┐
                                    │ Per-type retry + │                │   PostgreSQL    │
                                    │ dead-letter queue│                │ 1 table         │
                                    │ app_statsstore.  │                │ per stat type   │
                                    │ <type>.retry.    │                └────────┬────────┘
                                    │   <tier>         │
                                    │ app_statsstore.  │
                                    │ <type>.dlq       │
                                    └──────────────────┘                         │ read
                                                                                 ▼
                                                                        ┌─────────────────┐
   External consumer ◄──── GET, Keycloak-secured, tenant-scoped ─────── │  Module 3       │
                                                                        │  Stats API      │
                                                                        └─────────────────┘
```

RabbitMQ, PostgreSQL, and Keycloak are **infrastructure dependencies**, not Stats
App deployables. Never describe them as application modules.

---

## 2. Modules

| Module | Gradle name | Responsibility |
|---|---|---|
| — | **stats-common** | Five things, and only these (D15): the shared envelope model, the stat-type enum, the AMQP topology constants, RFC 9457 Problem Details with the error catalogue and safe field errors, and Draft 2020-12 schema validation against the normative catalogue in `docs/schemas/`. **No module-specific application logic**, ever. The validator lives here because the schemas are normative for the whole project and the Java oracle must stay in parity with the Python CI gate; it is a shared capability, not Input's business logic. |
| 1 | **stats-input** | HTTP POST endpoint. Authenticates the caller, validates the payload against the schema for its stat type, then publishes to the `app_stats_input` exchange with the stat type as routing key. Returns 2xx **only after publisher confirm**. Does **not** touch the database. |
| 2 | **stats-writer** | Consumes from the per-type RabbitMQ queues and writes to PostgreSQL. The **only** component with database write access. Owns retention/cleanup. |
| 3 | **stats-api** | Read-only REST endpoints. Keycloak-secured, role-based, tenant-scoped. **GET only** — never writes. Has no broker role. |

Each application module is a **separately deployable Docker image** — answered
(Q2). Module dependency rule: each may depend on `stats-common`; **never** on
another application module.

---

## 3. Tech stack

| Concern | Technology |
|---|---|
| Build | Gradle (multi-module), Gradle Wrapper only |
| Language / JDK | **Java 21** |
| Framework | **Spring Boot 3.5.16** — pinned in `gradle/libs.versions.toml` as a single entry. Not provisional: Q1a was answered 18 Aug 2026 and there is no reference repo to reconcile against. Module build files never declare their own versions |
| Messaging | RabbitMQ via Spring AMQP |
| Database | **PostgreSQL — the only V1 persistence technology** |
| Migrations | Versioned. **Flyway** (ADR-003 D6) |
| Auth | Keycloak (OAuth2 / OIDC) via Spring Security OAuth2 Resource Server |
| Config | `application.properties`, typed configuration properties |
| Testing | JUnit, Spring Boot Test, **Testcontainers for PostgreSQL and RabbitMQ** |

> **MongoDB is out of scope.** Do not introduce MongoDB drivers,
> `MongoRepository`, TTL-index code, Mongo Compose services, `_id` semantics, or
> Mongo terminology ("collection", "document"). This was decided, not deferred.

---

## 4. Domain glossary

Use these terms exactly. Do not invent synonyms.

| Term | Meaning |
|---|---|
| **Stat type** | The classification of a record: `lorastats`, `ltestats`, `sysstats`, `netstats`, `omsstats`. Determines routing key, queue, consumer, and **table**. |
| **Envelope** | The common fields present on every record regardless of type: `uid`, `tenant`, `maintenant`, `clsbox`, `type`, `version`, `ts`, `data`, `status`. |
| **Payload** | The `data` object — the only part that differs between stat types. |
| **tenant** | The tenant the gateway is assigned to. **The isolation field.** |
| **maintenant** | The *managing* tenant. Often identical to `tenant` in sample data — **this is a trap, see §6**. Never used for isolation. |
| **clsbox** | **The device identifier field.** 12 hexadecimal characters. This is the name on the wire, in the schema, in Java, in the PostgreSQL column, and in API responses (ADR-007 §1). The SRS called it `gateway`; nothing in production ever has. |
| **gateway** | The *physical device* — used in prose only ("160,000 gateways", "a gateway with a bad clock"). **Never a field name.** Do not mechanically rewrite this word to `clsbox`. |
| **ts.server** | The canonical **arrival** clock — Heimdal's receipt time. What the SRS called `ts.processor`. Clock skew is measured against it (ADR-007 §3). |
| **ts.device** | When the gateway measured the record. **Retention keys on this**, not on `ts.server` (STA-23B). |
| **Passthrough members** | `ts.datastore`, `ts.converter`, `ts.expiresOn`, `status`, `version` — accepted, stored, returned verbatim, and **never read**. |
| **Heimdal Core** | The Datahub Core module that sends us data. Upstream, owned by ZC. Not ours. |
| **Statsstore** | The persistence layer — Module 2. Also the queue-name prefix. |
| **DLQ** | Dead-letter queue. Per stat type. Holds messages that failed processing after the retry limit. |

### Record shape

This is the **real production shape**, from the sample of 29 August 2026. It is not
an idealisation of it — build against this.

```json
{
  "uid": "CLSCON-11d3ec4c-f6e1-403a-a9ef-b6f5fe909eee",
  "tenant": "mzc",
  "maintenant": "mzc",
  "clsbox": "04B64810652B",
  "type": "lorastats",
  "version": 1,
  "ts": {
    "server":    1787833739,
    "datastore": 1787833739,
    "converter": 1787833739,
    "device":    1787833739,
    "expiresOn": "2026-08-27T12:28:59.074Z"
  },
  "data": { },
  "status": ["conversion ok"]
}
```

`ts.server`, `ts.datastore`, `ts.converter` and `ts.device` are **Unix timestamps in
seconds**. `ts.expiresOn` is an **ISO-8601 string** — the odd one out, and typing it
as an epoch integer throws on every production record.

**Required:** `uid`, `tenant`, `maintenant`, `clsbox`, `type`, `ts` (with `server`
and `device`), `data`. Everything else is optional and never read.

`uid` is `CLSCON-`-prefixed, not a bare UUID. No format is enforced — ZC guarantees
uniqueness, not a shape.

> **omsstats — resolved (ADR-004, ACCEPTED; amended by ADR-007 §2).** Heimdal
> already sends the canonical `ts.server`. The Stats App performs **no timestamp
> normalisation** and there is no `ts.converter`-driven transformation anywhere in
> the codebase. But a record carrying `ts.converter` is **valid** — production sends
> it on every record, and the rule that called it invalid rejected 100% of ingest.
> Accepting the field is not the same as acting on it. The v1.0 SRS section is out
> of date.

---

## 5. Non-negotiable rules

These come from the SRS as MUST requirements. Do not violate them, and flag it if
a change would.

1. **The API is read-only.** GET endpoints only. No POST, PUT, PATCH, or DELETE
   in Module 3, ever — they return 405 with no side effect. *(STA-32, STA-32A)*
2. **Only Module 2 writes to the database.** Modules 1 and 3 never hold a
   write-capable database dependency. *(STA-32)*
3. **Tenant isolation is enforced server-side on `tenant`.** Never trust a tenant
   value supplied by the caller. Derive it from the validated token claim and put
   it in every query, count, and cursor. *(STA-35, STA-35A)*
4. **One PostgreSQL table per stat type.** Types are never mixed into a shared
   table. *(STA-21, STA-21A)*
5. **One durable queue per stat type**, named `app_statsstore.<type>`, plus
   **three** per-type retry queues `app_statsstore.<type>.retry.<tier>` and a
   per-type DLQ `app_statsstore.<type>.dlq`. A shared queue is not permitted.
   All tier delays and the maximum attempt count are externalised to
   `application.properties` — never hard-coded — with starting values of 30 s,
   5 min, and 30 min. Immediate requeue is never used for a persistent failure.
   **The DLQ itself carries no TTL and no expiry of any kind** — a TTL'd
   dead-letter queue is silent data loss (ADR-002 §12, 29 Aug 2026).
   *(STA-10A, G1/G4 ratified 18 Aug 2026)*
6. **Adding a new stat type must not require changing existing types.**
   New type = new schema + routing key + queue + consumer + table + endpoint
   operation + fixtures + metrics. *(STA-14, STA-14A, STA-22)*
7. **Records failing the ENVELOPE are rejected with HTTP 400 and logged.** Never
   silently dropped, and nothing is published. **Records failing the per-type
   PAYLOAD schema are dead-lettered by the Writer, not 400'd** — by then the record
   has been accepted, publisher-confirmed, and published, and Heimdal retries only
   on a non-2xx, so a 400 at that point would be unrecoverable data loss. Two
   gates, two consequences: `validateEnvelope()` at Input, `validate()` at the
   Writer. *(STA-03, STA-03A, ADR-007 §5)*
8. **A 2xx is returned only after RabbitMQ publisher confirm.** If the broker is
   unavailable or confirmation times out, return 503. No local buffering — Input
   is stateless. Heimdal owns retry on any non-2xx. *(STA-04A, STA-06A)*
9. **Failed writes go to the per-type dead-letter queue.** Never silently
   discarded. Acknowledge only after a successful PostgreSQL transaction.
   *(STA-12, STA-25, STA-25A)*
10. **Queues, exchange, and bindings are durable; messages are persistent.**
    They survive a broker restart. *(STA-13, STA-13A)*
11. **Retention is configurable via `application.properties`, default 90 days,
    and keys on `ts.device`.** *(STA-23, STA-23A, STA-23B)*
12. **The API is versioned.** *(STA-38, STA-38A)*
13. **`uid` is its own PostgreSQL column, covered by a unique constraint.** Not a
    database-generated identifier. On the partitioned design the constraint is
    `(ts_device, uid)` — **in that column order** — and is scoped per partition; a
    global unique index is not possible on a partitioned table. `ts_device` leads
    so the key matches the partition key and the mandatory
    `(ts_device DESC, uid DESC)` sort. Redelivery still cannot duplicate, because a
    redelivered record carries the same `ts_device`. A suppressed
    `ON CONFLICT DO NOTHING` insert is **metered, never silent** —
    `records_duplicate_suppressed{type}`. `tenant` is deliberately **not** in the
    key; the counter is what would reveal a cross-tenant `uid` collision.
    *(STA-21B, STA-25A, STA-27A, ADR-003 — SRS amended 24 Aug 2026.)*

---

## 6. Known traps

Things that are easy to get subtly wrong here.

**`tenant` vs `maintenant` look identical in the sample data.**
The SRS examples frequently have the same value in both fields. An implementation
that filters on the wrong one will pass every test written against those samples
and leak data in production. Always write fixtures where the two values
**differ**. This is the single highest-risk item in the project.

**Head-of-line blocking with a shared queue.**
If all stat types share one queue, a poison message in one type stalls every
other type. The per-type queue structure is not decoration — it is why C1 was
escalated and answered. There must be a test proving a poison message in one type
does not delay another.

**Retention keys on `ts.device`, and that has a consequence.**
A gateway that was offline delivers records whose `ts.device` is weeks older than
`ts.server`. Those records are near expiry the moment they arrive. This is the
**answered and accepted business rule** — do not "fix" it by quietly switching to
`ts.server`. Document it and cover it with boundary tests.

**Unix seconds, not milliseconds.**
`ts.server` and `ts.device` are in **seconds**. Treating them as milliseconds
silently produces dates in 1970.

**Unsorted pagination is not stable.**
Without an explicit deterministic sort, a paginated query can return the same row
on two pages or skip one entirely. Always sort on an indexed field with a unique
tie-breaker — `(ts_device DESC, uid DESC)` — always, not only when the caller asks.

**A 200 response is a promise.**
If Module 1 returns 200 before the broker confirms, a broker failure in that
window loses the message *and* Heimdal will not retry, because it saw success.

**Row-by-row deletion at this volume is a trap.**
At up to 115.2 million records/day, an unbounded `DELETE` creates table and index
bloat, long transactions, and lock contention. Evaluate time partitioning first;
if not partitioning, use bounded, indexed, resumable deletion batches and accept
the trade-off explicitly.

**Modules declaring the same topology can break startup.**
If two modules declare the same exchange or queue with different arguments,
RabbitMQ rejects the mismatched declaration with `RESOURCE_LOCKED`. Declarations
must come from shared constants in `stats-common` and be identical and idempotent.

**One stat type can speak several dialects at once, and nothing on the record says
which.**
Three consecutive production `lorastats` records used three incompatible payload
shapes — bare names with integers, bare names with strings (`"176"`), and
`lora_`-prefixed names. **All three carried `version: 1`.** Anything that branches
on `version` to pick a parser is wrong. We encode one dialect and dead-letter the
rest (ADR-007 §4); do not "fix" a DLQ spike by widening the schema to absorb them.

**The SRS field names are not the wire field names.**
Every envelope name in SRS v1.0 — `gateway`, `ts.processor` — is wrong, and the
whole document set agreed with it consistently for months. Consistency across
documents is not evidence of correctness when they all derive from one source. If
you are adding a field, check it against a production record, not against another
document.

**A required field that is absent because the device is broken.**
`netstats` required `ip`/`gw`/`mask`/`dns` on every interface, which dead-lettered
every record from a modem that never came up — exactly the record ops most needs.
Before making a field required, ask what the record looks like when the thing it
describes is *failing*.

**Wire types differ per stat type for the same concept.**
`timespan` is an integer in `lorastats`/`omsstats`/`netstats` and the string
`"300"` in `sysstats`. Every `ltestats` numeric is a string. There is no single
coercion that is right everywhere; parse per type and range-check before conversion.

**Unknown-field policy is a real decision, not a Jackson default.**
Whether an unexpected field is accepted or rejected must be a deliberate,
documented choice — not whatever the deserialiser happens to do.

---

## 7. Open questions — do NOT assume answers

Most of the v1.0 blockers are now closed. What remains is below. Do not invent an
answer to any of these. If a task requires one, say so and stop.

### Answered 18 August 2026 — do NOT reopen

All six §2b items were answered on 18 Aug 2026. The decision record, with the reasoning and the
derived engineering decisions, is `docs/decisions/2B-ANSWERS-18AUG2026.md`. Read it before touching
`netstats` validation, Input authentication, Keycloak config, or capacity numbers.

| Ref | Answer | Consequence |
|---|---|---|
| Q1a | **There is no internal reference repo.** Nothing to wait for | We define our own CI, logging, secrets and deployment conventions. **Spring Boot 3.5.16 is now pinned, not provisional** — there is nothing left to reconcile it against |
| Q4a | `eth0` and `wwan` **mandatory**; other interfaces **allowed**; `apipa` **optional**; counters **stay strings on the wire, stored as `BIGINT`** | `docs/schemas/netstats-v1.schema.json` is strict and no longer a draft. `apipa` is optional because ZC's own sample omits it on `wwan`. The schema caps counters at 19 digits — **the Writer must still range-check** against the signed `BIGINT` maximum, or an overflow corrupts the value silently. IPv6 stays unsupported |
| Q8a | **Deferred by decision.** Build Input without Heimdal auth for now | Auth is a config-selected filter: `stats.input.auth.mode=none\|mtls\|oauth2`, **no default in code**, `WARN` on every startup when `none`, and a test that refuses to start under the `prod` profile. Until ZC answers, anything reaching the Input port can write |
| Q8b | **Engineering proposal, live in the dev realm** | Realm `statsapp`, clients `stats-api`/`stats-input`/`stats-dev-cli`, roles `stats.readOnly` and `stats.ingest`, tenant claim **`tenant`, single-valued String**. Working realm at `docs/setup/keycloak-realm-statsapp.json`. Single-valued on purpose: a multi-valued claim turns isolation into `tenant IN (...)`, and one misconfigured user then reads several tenants with every test still green |
| Q13a | **Assume synchronised 10-minute boundaries** until Heimdal telemetry proves otherwise | Design target is **13,333/s** (10× average — 800,000 records per cycle landing in 60 s). Writer prefetch starts at 500; Writer is sized for drain (~2,000/s per type), not for peak. Queue depth spiking each cycle is the buffer working — alert on **oldest message age**, never on depth. `max_wal_size` sized for the burst, not the average |
| Q13a (replay) | **Ask Heimdal to cap replay at 2× nominal (2,667/s)** | A 6-hour outage is 28.8M messages and about 3 hours of replay at that cap, holding total ingest at 4,000/s. 503 is the backpressure channel and is the mechanism working, not an incident. Replayed records are duplicates by definition and `ON CONFLICT DO NOTHING` absorbs them |
| Q11a | **Built**, then **rebuilt on production 29 Aug 2026 (ADR-007)**. 77 fixtures across `valid/`, `invalid/`, `edge/`, `malformed/` | `docs/schemas/fixtures/`, catalogued in `MANIFEST.md`. `docs/schemas/validate-fixtures.py` asserts **87** and exits non-zero for CI. The 18 Aug set found three real schema gaps; the 29 Aug production sample found that **four of the five payload schemas and every envelope field name were wrong**, and inverted four fixture assertions outright |

### Still needs ZC / Hasan

| Ref | Question | Blocks |
|---|---|---|
| Q8a-2 | Heimdal inbound auth, when ZC is ready: mTLS or OAuth2 client credentials; issuer, audience, client identity, rotation | Nothing today — deferred by decision. **Must close before any shared deployment** |
| Q8b-2 | Confirmation of the proposed Keycloak realm, client, issuer, audience, role names and tenant claim | Nothing today — the proposal is live and confirmation is a config change |
| Q13b | **Gateway report jitter.** Do gateways spread reporting across the 10-minute cycle, or fire on the boundary? | Worth 10× on hardware. The single most valuable outstanding question |
| Q4b | `netstats` IPv6: do any gateways report IPv6 addresses? | One `$def` widens if yes; no code change |
| Q13c | Agreement on the 2× replay rate cap | Our own rate limiting cannot substitute for Heimdal honouring it |
| **Q15a** | **Which `lorastats` payload dialect is canonical, and are the others being retired?** Three consecutive production records used three incompatible shapes — bare/integer, bare/string, `lora_`-prefixed/integer — all carrying `version: 1`. Either a migration is in flight or three firmware generations are in the field. | **The most urgent open item.** Nothing is blocked — the schema assumes bare/integer — but that assumption dead-lettered ~2/3 of `lorastats` in the sample, and ADR-002 §12 forbids a DLQ TTL, so the DLQ grows until this is answered |
| Q15b | **Is `ts.device` genuinely device-assigned?** It equals `ts.server` in 14 of 15 production records and differs by 1 second in the fifteenth. If it is stamped on arrival, retention-on-`ts.device` is retention-on-arrival | Not blocking. Would settle whether the offline-backfill trap and the late-arrival policy (D5b, T-026) describe anything real |
| Q15c | Does Heimdal POST `_id`? It is in the supplied dump and is a Mongo artifact | Nothing — accepted under D14 either way |
| Q15d | Is `status` ever anything but `["conversion ok"]`? If it can report a *failed* conversion, we are storing records upstream already knows are bad | Nothing today. Would justify a metric |

### Decided 29 August 2026 — do NOT reopen

Six items that had been filed as questions for someone else. Four were never anyone
else's to answer. **Approver:** Naman Vashishtha. Full reasoning and the framing to take
into the remaining conversations: `claude/OPEN_QUESTIONS_STATUS_29AUG2026.md`.

| Ref | Decision | Lands in |
|---|---|---|
| Q14a | **Clock skew: reject at Input** when `ts.device > ts.server + stats.input.validation.max-clock-skew` (default `PT5M`). 400 with field code `clock_skew_exceeded`, metered as `records_clock_skew_rejected{type}`. Accept inside the tolerance. The check is cross-field, so it is code in Input, not a schema rule — the five `edge/<type>-clock-skew-future.json` fixtures stay schema-valid and carry `$expect: "input-reject"` | ADR-003 D9; `heimdal-input-contract-v1.md` §2/§4; T-013 |
| Q3a | **Only broker-connected modules declare topology** — Input and Writer, from `stats-common` constants. `stats-api` gets no RabbitMQ connection. Platform ack is a courtesy; T-032 is the enforcement | ADR-002 §11 |
| Q5a | **115.2M records/day across five types** (160k gateways × 6/h × 24h × 5). The internal "around 10 million" is withdrawn. This never blocked freezing the schema: every schema decision is identical at both figures — the delta is hardware, not rework | ADR-003; capacity tables |
| — | **DLQ retention: no TTL, ever.** A TTL'd DLQ is silent data loss. Alert on depth > 0 and oldest-message age; manual disposition within 14 days; redrive by an authorised ops role only, per type, capped at 2× nominal, root cause first | ADR-002 §12; `amqp-topology-v1.md` §6 |
| — | **`pg_partman` is never a dependency.** Partition pre-creation is a scheduled task in `stats-writer` behind the D3 advisory lock | ADR-003 D1 |
| D5b | **Engineering agrees the late-arrival policy** (skip, meter `records_expired_on_arrival`, ACK) and adds an alert when the counter is non-zero. **Product sign-off is still owed** — see below | ADR-003; T-026 stays BLOCKED |
| **ADR-007** | **The production wire format is the contract.** `clsbox` not `gateway`; `ts.server` not `ts.processor`; no rename layer anywhere. `ts.converter` accepted, preserved, metered — ADR-004's rejection clause withdrawn. Two validation gates: envelope at Input (400), payload at the Writer (dead-letter). One `lorastats` dialect encoded, the rest dead-lettered. `netstats` interfaces require only `tx`/`rx`. `ltestats` numerics are strings and the field is `access_Technology` | ADR-007; amends ADR-002 §12, ADR-003, ADR-004 |

**The redrive trap to carry into the runbook:** a message that sat in the DLQ long enough
becomes `expired_on_arrival` the moment it is redriven, because its `ts.device` has aged
past the retention window. A redrive after ~90 days is a no-op that increments a counter,
not a recovery.

### Ours to decide — decide deliberately, record as an ADR, do not escalate

Hasan explicitly returned these to engineering. They are not blockers; they are
unwritten ADRs. The recommended position is given, but it is not yet ratified.

| Ref | Decision | Recommended position |
|---|---|---|
| D7 | Consumer ACK timing | Manual ACK **after** the successful PostgreSQL transaction |
| D11 | Individual vs batched writes | **Amended 18 Aug 2026 (G3).** Target is bounded micro-batching (100–500 records or 50–200 ms, whichever first) with single-record fallback to isolate a poison record — which preserves the per-record DLQ precision the original position was protecting. Build individual inserts **first**, behind a batch-shaped interface, so batching becomes a tuning change against a real benchmark rather than a rewrite. Supersedes "individual first; batching breaks per-record DLQ precision" |
| D12 | Invalid filter combinations | 400 `invalid_time_range` on an inverted range; document the max query window |
| D13 | Health checks | Separate liveness/readiness; readiness probes only that module's real dependencies |
| D1 | Log format | Structured JSON to stdout; `uid` as correlation ID |
| D14 | Unknown fields *(was mislabelled `D6` — see §8)* | Accept and preserve unknown `data` fields; reject invalid envelope fields |
| **D14b** | **Where an unknown `data` member is *stored*.** D14 says preserve; ADR-003 D2 gives the four typed types **typed columns only**, which have nowhere to put one. `StatsEnvelope.data` is a `JsonNode` so Input preserves it and republishes it byte-identically (DL-020, DL-050) — the loss was at the **Writer's INSERT**, where an unmapped key was silently dropped. `passthrough` does **not** cover this: ADR-003 scopes it to the five *envelope* members. `netstats` was never affected — its `jsonb` interface map was chosen for exactly this reason | **DECIDED 3 Sep 2026 — DL-095.** Each typed table gains a nullable `data_extra jsonb`; the Writer preserves unmapped `data` members there verbatim and meters `records_unmapped_data_field{type}`. NULL on almost every row, so it does not reintroduce the key-name overhead D2 rejected. **Every new stat-type table must carry the column** — omitting it silently reopens the drop |
| — | Gradle DSL, package layout, CI shape | **Ours — Q1a closed, there is no reference service to match.** Kotlin DSL, version catalog, `com.zenner.datahub.stats.<module>`, `./gradlew build` plus Testcontainers on every push (ADR-001) |

**Decided in ADR-003:** PostgreSQL 17; daily `RANGE (ts_device)` partitions with
detach-and-drop retention; typed columns for the four stable types and a
`jsonb` hybrid for `netstats`; `stats-writer` owns retention behind an advisory
lock; Flyway; duplicate suppression metered rather than
silent, with `tenant` out of the key until the metric says otherwise; a
`(tenant, uid)` secondary index for the `uid`-only lookup.

### Needs product / operations

| Ref | Question | Blocks |
|---|---|---|
| D5a | Backup, PITR, and restore targets — an RPO/RTO number. **Engineering proposal to take to product: RPO ≤ 5 min via continuous WAL archiving; RTO 1 h for a single partition, 24 h for a full cluster restore.** RPO is the tight one because lost data is unrecoverable — Heimdal retries only on a non-2xx, so anything we 200'd and then lost is gone. RTO is deliberately loose: this is 90-day telemetry, not financial records, and at multi-terabyte scale a tight RTO may not be purchasable. Framing: *"if we lost the last 15 minutes, or took 8 hours to come back, what actually breaks?"* *(BR-30, BR-35, ADR-003 D5)* | Production readiness; T-051 |
| D5b | Late-arrival policy. A record can arrive with `ts_device` older than the retention window, so no live partition exists for it. Skip the write, meter `records_expired_on_arrival`, ACK — rather than write a row that retention removes minutes later. **Engineering agreed 29 Aug 2026 and added an alert when the counter is non-zero; the product signature is still owed.** It is a deliberate data-loss-by-design path, and the only implementable one: the alternative is writing into a partition that does not exist. *(ADR-003)* | Writer, Phase 2 — **T-026 stays BLOCKED** |

**Answered since v1.0 — do not reopen:** database (PostgreSQL only), queue
structure (per type), language/JDK (Java 21), deployables (one image per module),
`omsstats` scope and timestamp handling, `uid` uniqueness and storage, retention
basis (`ts.device`), tenant presence (always), isolation field (`tenant`),
publisher-confirm timing, broker-unavailable behaviour, error shape (RFC 9457),
dominant query pattern, exchange type (Topic), dead-lettering / per-type metrics /
horizontal scaling all in v1 scope.

**Endpoint definitions:** no larger spec exists and none is coming. ZC's answer was
*"please create an OpenAPI definition and we will confirm/decline it."* Writing the
OpenAPI is our action, not a wait on them.

---

## 8. Requirement traceability

Requirements use the `STA-nn` prefix from the SRS. The v1.2 amendment adds
letter-suffixed IDs (`STA-nnA`) for requirements it restates or sharpens, plus
`NFR-01`–`NFR-06` for operational requirements. When implementing something that
satisfies a requirement, reference it in the commit message and in a comment on
the relevant class or method, along with the governing ADR.

> **ADR numbering — `ADR-nnn` always means this repository's ADR (G5, 18 Aug 2026).**
> `Datahub_Core_StatsApp_ARD_v1_1.pdf` carries its own ADR-001…010 whose numbers
> mean **different things** — its ADR-004 is at-least-once delivery, ours is OMS
> timestamp normalisation. When quoting the ARD anywhere in code, commits, or
> documents, write it as **`ARD-ADR-nn`**. The repository's ADR-001…007 remain
> authoritative; the ARD is a v1.0-derived proposal and does not outrank the v1.2
> amendments.

> **Decision-ID namespaces (24 Aug 2026).** Four different schemes use a bare `D`,
> and they had begun to collide. Write them as follows, always:
>
> | Form | Means | Source |
> |---|---|---|
> | `DL-nnn` | **An entry in `claude/DECISIONS.md`** — the decision log | `claude/DECISIONS.md` |
> | `STA-nn`, `NFR-nn` | Requirements | SRS + v1.2 amendment |
> | `Dn` *(no hyphen)* | A decision question from the answer set | `Stats_App_Answers_v1_0.docx` |
> | `D-nn` *(hyphen)* | A stop/go register row | `claude/CODE_START_CHECKLIST.md` §A |
> | `ADR-003 Dn` | A decision **internal to** ADR-003 — always write the ADR prefix | `ADR-003` |
> | `Gn` | A gate decision of 18 Aug 2026 | `CODE_START_CHECKLIST.md` §A2 |
>
> **The unknown-field policy was mislabelled `D6` and is now `D14`.** Answer-set
> `D6` already means *"the published message is identical to what was received"*,
> and ADR-003's own `D6` is the migration tool. Three meanings for one label is the
> same failure `ARD-ADR-nn` was introduced to fix (G5). `D14` is the next free
> answer-set-style number and is an **engineering** decision, not one ZC returned.

| Range | Area |
|---|---|
| STA-01 … STA-06 | Input Module (JSON Endpoint) |
| STA-10 … STA-14 | RabbitMQ / buffering |
| STA-20 … STA-27 | Stats Writer (Statsstore) |
| STA-30 … STA-39 | Stats API |
| NFR-01 … NFR-06 | Operations, logging, security, deployment |

### ADR status

This table mirrors the `**Status:**` field in each ADR file. **The ADR file is the
record of decision; if the two ever disagree, the file wins and this table is
wrong.** Reconciled 18 August 2026.

| ADR | Subject | Status | Residual |
|---|---|---|---|
| ADR-001 | JVM and Spring platform baseline | **APPROVED** | Java 21; Spring Boot **pinned** at 3.5.16 (G6) — not provisional, because Q1a closed on 18 Aug 2026. There is no internal reference project, so CI, logging, secrets and deployment conventions are defined by this project (ADR-001, 2B-ANSWERS §1). No residual. |
| ADR-002 | Per-type AMQP topology and ownership | **APPROVED** | Queue naming and three retry tiers fixed by G1/G4. **Amended 29 Aug 2026:** §11 closes Q3a — only broker-connected modules declare topology and `stats-api` gets no broker connection; §12 decides dead-letter retention — **no TTL, ever** — with alerting, 14-day manual disposition and per-type authorised redrive. No residual. |
| ADR-003 | PostgreSQL retention, partitioning, indexes | **APPROVED** (24 Aug 2026) | PG17, daily `RANGE (ts_device)` partitions, typed columns, Flyway; key order `(ts_device, uid)` per G2, with STA-21B/STA-25A amended to match and duplicate suppression metered. Secondary index `(tenant, uid)` carries the `uid`-only lookup, which cannot prune partitions. Restore is per-partition (D5). **Amended 29 Aug 2026:** D1 makes partition pre-creation a scheduled task in `stats-writer` behind the D3 advisory lock, with `pg_partman` never a dependency; **D9 closes Q14a** — a `ts.device` beyond the configured skew tolerance is rejected at Input. Two rows remain open inside an approved ADR: **RPO/RTO targets** still need a number from product/ops (D5a), and the **late-arrival policy** has engineering agreement but still needs product sign-off (D5b). Approval does not close either. |
| ADR-004 | OMS timestamp normalisation | **ACCEPTED — amended 29 Aug 2026 by ADR-007 §2** | No normalisation; publication is byte-identical to the received record, which is what makes ADR-003's per-partition duplicate suppression work. **Clause 3 is withdrawn:** a record carrying `ts.converter` is **valid** — production sends it on 100% of records. Accepted, preserved, never read, metered. Cite the ARD's different ADR-004 as `ARD-ADR-04`. |
| ADR-005 | Delivery semantics and idempotency | **ACCEPTED with implementation defaults** | Writer batching amended by G3. Retry/DLQ handoff must be publish-confirmed *before* ACKing the original — ACK-then-publish opens a silent loss window. Duplicate outcome is `ON CONFLICT (ts_device, uid) DO NOTHING`, metered. Replay spikes the duplicate counter by design; do not alert on it. Q13c (2× replay cap) is not enforceable by us. |
| ADR-007 | The production wire format is the contract | **ACCEPTED** (29 Aug 2026) | `clsbox`/`ts.server` end to end with no rename layer; `ts.converter` accepted and metered (amends ADR-004); two validation gates with different consequences; one `lorastats` dialect encoded and the rest dead-lettered. **Four open questions raised, Q15a the urgent one** — which dialect is canonical. Also amends ADR-003 (column names) and ADR-002 §12 (DLQ volume). |
| ADR-006 | Tenant isolation and API security | **ACCEPTED** | The isolation decisions are settled and buildable; only ZC's confirmation of the Keycloak values (Q8b-2) is outstanding, and it is a config change. Proposed realm is live in dev — read role is `stats.readOnly`, **not** the bare `readOnly` placeholder. Input auth is deferred by decision (Q8a): `stats.input.auth.mode` has no default in code and `prod` must refuse `none`. |

---

## 9. Working agreements for AI assistance

- **Ask before assuming.** If a task depends on anything in §7, stop and say
  which question blocks it rather than picking a plausible answer.
- **Cite the requirement.** When implementing behaviour driven by the SRS, name
  the `STA-nn` ID and the governing ADR.
- **Quiz before changing.** Before implementing a change, quiz the requester
  on the decisions it depends on and the trap it can fall into. A wrong or
  unsure answer is answered with an explanation, then the quiz is asked again.
  Nine of the eleven ways this system can lose a record fail silently, and a
  confident wrong mental model is what an explanation alone cannot detect.
  *(DL-005, `IMPLEMENTATION_RULES.md` §9)*
- **Log the decision.** A change that alters code, contract, or operational
  behaviour adds its `DL-nnn` entry to `claude/DECISIONS.md` in the same
  change, with the reasoning — not afterwards. *(DL-002)*
- **Prefer the boring solution.** This is infrastructure code that will be
  maintained by people who did not write it.
- **No silent error handling.** Every failure path either returns a meaningful
  error to the caller, dead-letters the message, or logs at `WARN`/`ERROR` with
  enough context to act on. Never swallow.
- **New stat type = additive change only.** If a proposed change to support a new
  type modifies existing type handling, that is a design smell — raise it.
- **Tests before merge.** Especially for tenant isolation, which is a security
  control, and for anything whose failure is invisible in a naive test.
- **Do not add dependencies casually.** Prefer what Spring Boot already brings in,
  and explain why the platform libraries cannot provide the capability.
- **No MongoDB.** If a suggestion involves a collection, a document, an `_id`, or
  a TTL index, it is wrong for this project.

---

## 10. Reference documents

Read in this order. Where two documents disagree, the **later amendment wins**,
and the v1.0 `.docx` files are historical context only.

Everything lives under this directory. `README.md` is the index; start there if
you are new.

### Working documents — `claude/`

All of them live in `claude/`, including this file. The repository root holds
only `README.md` (the index) and a `CLAUDE.md` pointer that imports this file.

| Document | Contents |
|---|---|
| `README.md` (repository root) | Index and orientation for the whole repository |
| `CLAUDE.md` (repository root) | Pointer only — a single `@claude/CLAUDE.md` import so this file auto-loads. Never holds content. |
| `claude/CLAUDE.md` | This file — project context, non-negotiable rules, traps, open questions |
| `claude/DECISIONS.md` | **Every meaningful decision and why it was taken** — architecture, libraries, conventions, and the small choices that never justified an ADR. `DL-nnn` IDs. Start here for *why is it like this?* |
| `claude/FLOW.md` | **The execution flow** — how a record travels from Heimdal to an API response, every failure exit, and the eleven windows where a record could be lost. Marks what is BUILT versus SPECIFIED |
| `claude/PROJECT_STATUS_AND_PLAN.md` | Current status, open items by owner, phased plan, delegation |
| `claude/IMPLEMENTATION_RULES.md` | Detailed implementation rules and the required AI workflow |
| `claude/DEVELOPMENT.md` | How to build, run, test, and contribute |
| `claude/SKILLS.md` | Repeatable procedures: add a type, review isolation, write tests, diagnose a queue |
| `claude/CODE_START_CHECKLIST.md` | Stop/go decision register and the approved implementation sequence |
| `claude/OPEN_QUESTIONS_STATUS_29AUG2026.md` | Status of every open question and decision as at 29 Aug 2026, the six decisions taken that day with their reasoning, what actually blocks which task, and the tracker inconsistencies found |

### Authoritative specifications — `docs/`

| Document | Contents |
|---|---|
| `docs/requirements/SRS-v1.2-amendment.md` | Normative requirements, incorporating all answers |
| `docs/requirements/BRD-v1.2-amendment.md` | V1 business scope, in/out, acceptance criteria |
| `docs/requirements/ARB-v1.2-amendment.md` | Architecture decisions and ARB decision log |
| `docs/decisions/ANSWER_RECONCILIATION.md` | Every answered question and its implementation consequence |
| `docs/decisions/POSTGRESQL_BASELINE.md` | Persistence direction, table/index draft, sizing |
| `docs/architecture/adr/ADR-001…007` | Platform, AMQP topology, retention/indexes, OMS timestamps, delivery/idempotency, tenant isolation, **production wire format** |
| `docs/architecture/Stats_App_Flow.png` | End-to-end flow diagram |
| `docs/contracts/heimdal-input-contract-v1.md` | Inbound HTTP contract, response matrix, auth |
| `docs/contracts/amqp-topology-v1.md` | Exchange, per-type queues, retry/DLQ, redrive |
| `docs/contracts/openapi.yaml` | **The API contract.** OpenAPI 3.1, GET-only, `/v1/stats/{type}`. Draft pending ZC confirmation; gates Phase 4 |
| `docs/contracts/openapi-v1-design.md` | Design input for the above. Converted 21 Aug 2026 — the YAML wins where they differ |
| `docs/schemas/` | JSON Schema catalogue, per-type schemas, the 68-file fixture set (`fixtures/MANIFEST.md`), and `validate-fixtures.py` for CI |
| `docs/decisions/2B-ANSWERS-18AUG2026.md` | The seven §2b answers of 18 Aug 2026 and every engineering decision derived from them |
| `docs/setup/keycloak-realm-statsapp.json` | Importable dev realm — proposed Q8b values, four users built for specific tests |
| `docs/prompts/CLAUDE_PROMPTS.md` | Prompt templates for working on this repo |
| `docs/setup/VS_CODE_SETUP.md` | Local IDE setup |

### Parallel design proposal — reconcile, do not adopt wholesale

| Document | Note |
|---|---|
| `docs/source/Datahub_Core_StatsApp_ARD_v1_1.pdf` | **Architecture & Requirements Design v1.1 — the current ARD.** Supersedes `ARD_v1_0.pdf`, which is kept for provenance only. The most detailed design document in the set — full capacity model, envelope column types, per-type typed columns, endpoint paths, health-check matrix, test matrix, runbook outline. **But it is derived from the v1.0 sources** (SRS v1.0, Answers v1.0, netstats sample) and knows nothing about the v1.2 amendments, so it does **not** outrank them despite the later date. Its ADR-001…010 are cited as `ARD-ADR-nn` — see §8. Six conflicts with this file were ratified as gates G1–G6 on 18 Aug 2026 (`claude/CODE_START_CHECKLIST.md` §A2), and `docs/decisions/2B-ANSWERS-18AUG2026.md` supersedes its §18 rows O-02, O-04 and O-12. `docs/contracts/openapi.yaml` cites it as the source for §8.1/§8.2 and AD-14. The `Stats_App_OpenAPI_v1_draft.yaml` it references was never delivered — **we wrote it ourselves on 21 Aug; that chase is closed.** |

### Historical — `docs/source/`, superseded, provenance only

| Document | Note |
|---|---|
| `Datahub_Core_StatsApp_SRS_v1_0_EN.docx` | Original requirements. Says MongoDB; the `omsstats` section describes `ts.converter`. Both superseded. |
| `Stats_App_Answers_v1_0.docx` / `.txt` | The answered question set — **source of every decision in §5 and §7** |
| `Stats_App_ARB_v1.0.docx`, `Stats_App_BRD_v1.0.docx` | Superseded by the amendments above |
| `Stats_App_Internal_Decisions_v1.0.docx` | Internal positions. Its Mongo-era items (Q41, Q42) are void, and **its "around 10 million" volume figure is withdrawn** — Q5a closed at 115.2M/day on 29 Aug 2026. |
| `Stats_App_Questions_v1_0.docx` | The questions as originally sent |
| `netstats-supplied-sample.json` | The `netstats` sample from ZC; also a fixture under `docs/schemas/fixtures/valid/` |
| `Stats_App_Claude_Ready_PostgreSQL_Pack.zip` | Original delivery archive. Its contents are unpacked into `docs/`; the copy of `CLAUDE.md` inside it is **stale** — do not restore from it. |
