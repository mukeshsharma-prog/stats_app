# Stats App — Implementation Rules

> **Version 1.3 — 24 August 2026.** Aligned with the answered decisions in
> `docs/source/Stats_App_Answers_v1_0.docx`, the six gate decisions G1–G6, and the
> §2b answers of 18 August 2026. PostgreSQL is the only V1 database; all MongoDB
> references have been removed.
>
> **Changed in 1.3:** `uid` uniqueness is `PRIMARY KEY (ts_device, uid)` per
> partition, not a unique index on `uid` (§3, §5.1); the read role is
> `stats.readOnly`; the unknown-field policy is **D14**, not `D6`; `netstats` is
> strict; and **§5 has been reconciled — most of what it blocked is now closed.**
>
> Companion to `CLAUDE.md` (project context and non-negotiable rules). This file
> holds the detailed rules and the required working sequence.

## 1. Mission

Build the **Stats App** as a Datahub Core add-on that accepts statistics records from Heimdal Core, validates and buffers them through RabbitMQ, persists them per stat type in **PostgreSQL**, and exposes them through a tenant-scoped, read-only REST API.

The intended build is a multi-module Gradle project with three independently deployable application services:

```text
stats-common   shared envelope, DTOs, schema registry interfaces, shared errors
stats-input    HTTP POST -> authenticate -> validate -> AMQP publish
stats-writer   AMQP consume -> persist -> retention/indexes -> metrics
stats-api      GET-only API -> Keycloak authentication -> tenant-scoped reads
```

RabbitMQ, PostgreSQL, and Keycloak are **infrastructure dependencies**, not Stats App deployables. Never describe them as application modules. Each application module ships as its own Docker image.

## 2. Required reading order

Before planning or editing code, read in this order:

1. `CLAUDE.md`, then this file.
2. `CODE_START_CHECKLIST.md`.
3. `docs/requirements/SRS-v1.2-amendment.md` for the requirement, plus
   `docs/decisions/ANSWER_RECONCILIATION.md` for the decision that governs it.
4. The relevant ADR and contract under `docs/architecture/adr/` and `docs/contracts/`.
5. The relevant schema under `docs/schemas/`.
6. The relevant test criteria in the implementation request.

`PROJECT_STATUS_AND_PLAN.md` tells you where the project currently is and which
phase a task belongs to.

If two documents disagree, **do not pick an answer**. Quote the conflict, name the affected requirements, and request an ADR or approved amendment.

## 3. Non-negotiable invariants

These rules derive from the SRS and cannot be relaxed by a coding shortcut.

| Rule | Implementation consequence |
|---|---|
| The Stats API is read-only. | Module 3 exposes GET endpoints only. POST, PUT, PATCH, and DELETE return 405 and no API code path writes data. |
| The Stats Writer is the only database writer. | `stats-input` and `stats-api` must never hold a write-capable database dependency or call a writer service directly. PostgreSQL is the only V1 database; do not add MongoDB code or dependencies. |
| Tenant isolation is server-side. | Derive the effective tenant solely from the validated access token; add it to every repository query; never trust a request tenant parameter, header, or body. |
| Per-type isolation is required. | Every stat type has its own routing key, durable queue `app_statsstore.<type>`, three retry tiers `app_statsstore.<type>.retry.<tier>`, DLQ `app_statsstore.<type>.dlq`, consumer, PostgreSQL table, indexes, retention configuration, metrics labels, and endpoint operation. |
| New types are additive. | Adding a type must not modify code handling existing types. Extend through a registry/plugin-style adapter and add a new isolated artifact/configuration. |
| Retention keys on `ts.device`. | This is the answered business rule. A late-arriving record may be near expiry on arrival; that consequence is accepted, documented, and boundary-tested — not silently corrected to `ts.server`. |
| `uid` is a first-class column. | Store `uid` as its own PostgreSQL column. Never a database-generated identifier. Uniqueness is `PRIMARY KEY (ts_device, uid)` — in that column order (G2) — scoped **per partition**; a unique index on `uid` alone is not creatable on a table partitioned by `ts_device` (ADR-003). |
| Invalid ingress is visible. | Return 400 for validation failure, log safe metadata, publish nothing, and expose an error response that does not contain device-identifying values. |
| Write failure is visible. | Apply the approved retry policy and send permanently failed records to a DLQ; never swallow exceptions or drop a message. |
| Queues are durable and messages persistent. | The broker must survive routine restart without losing accepted messages. |
| A success response means broker acceptance. | Do not respond with success until the approved publisher-confirm condition completes. |
| Timestamps are Unix seconds. | Convert seconds correctly; never interpret payload timestamps as milliseconds. |

## 4. Data rules

All record types use the common envelope:

```json
{
  "uid": "<unique record id>",
  "tenant": "<assigned tenant>",
  "maintenant": "<managing tenant>",
  "clsbox": "<12-character hexadecimal gateway MAC>",
  "type": "<stat type>",
  "ts": { "server": 0, "device": 0 },
  "data": {}
}
```

Treat `tenant` and `maintenant` as different business fields. The approved query/isolation field is `tenant`; every test suite must contain fixtures where they differ. Every record carries a tenant — a missing tenant is a validation failure, never a placeholder write.

`netstats` is in V1 scope and its schema is **strict and final** — Q4a was answered on 18 August 2026. `eth0` and `wwan` are mandatory; additional interface names are allowed and validated against the same shape; `apipa` is optional; `tx`/`rx` stay digit-only strings on the wire and become `BIGINT` columns. The supplied sample is a positive fixture.

**The Writer must range-check counters.** The schema caps them at 19 digits, which is wider than the signed `BIGINT` maximum. The gap is not expressible as a regex, so an unchecked conversion overflows and corrupts the value with no error anywhere. Fixture: `invalid/netstats-counter-overflow.json`.

**OMS timestamps — ADR-004 is ACCEPTED, amended 29 Aug 2026 by ADR-007 §2.** Heimdal already sends the canonical `ts.server`. The Stats App performs no timestamp transformation and there is no `ts.converter`-driven logic anywhere. But a record carrying `ts.converter` is **valid** — production sends it on every record, and the rule calling it invalid rejected 100% of ingest. It is accepted, preserved, never read, and metered as `records_converter_ts_present{type}`. Accepting a field is not the same as acting on it.

## 5. Decisions that code must not invent

This table was a gate on writing code. **Reconciled 24 August 2026 — most of it is
now closed**, and leaving it stale was itself blocking work that had been unblocked
since 18 August.

| Decision | State | Still blocks |
|---|---|---|
| Spring Boot version + platform reference repository | **CLOSED.** 3.5.16 pinned (G6); Q1a answered — there is no reference repo, so CI, logging, secrets and deployment conventions are defined in ADR-001 | Nothing |
| Inbound Heimdal authentication and identity | **DEFERRED BY DECISION (Q8a).** Config-selected filter, no default in code, `WARN` on `none`, `prod` refuses `none`. **Risk accepted: the Input port is unauthenticated** | Nothing today. **Blocks any shared deployment** (Q8a-2) |
| `netstats` nested-interface rules | **CLOSED (Q4a).** Schema is strict. Residual: the Writer must range-check counters against the signed `BIGINT` maximum | Nothing. **IPv6 (Q4b) closed provisionally 4 Sep 2026 — no, for now (DL-110)**, with a metered per-field rejection code as the reopening trigger |
| Keycloak realm/client/issuer/audience/role and tenant claim | **CLOSED (Q8b, ADR-006).** Realm `statsapp`, audience `stats-api`, read role `stats.readOnly`, tenant claim `tenant` single-valued String; live in the dev realm | Nothing. ZC confirmation (Q8b-2) is a config change, because none of these has a default in code |
| Retry count, backoff, DLQ policy | **CLOSED (G1/G4, ADR-002).** Three tiers, fixed delays 30 s / 5 min / 30 min, externalised, never a multiplier. **DLQ retention closed 29 Aug 2026 (ADR-002 §12): no TTL, ever** — alert on depth > 0 and oldest-message age, manual disposition within 14 days, redrive by an authorised ops role per type at ≤ 2× nominal | Nothing. The redrive *runbook* is still to be written (T-052), but its policy is decided |
| PostgreSQL version, typed columns vs `jsonb`, partition key, migration tool | **CLOSED (ADR-003).** PG17, daily `RANGE (ts_device)`, typed for four types with a `jsonb` hybrid for `netstats`, Flyway | Nothing |
| Approved OpenAPI | **DRAFTED** — `docs/contracts/openapi.yaml`, 21 Aug 2026, awaiting ZC confirm/decline | Freezing the contract. Controllers wait on confirmation |
| Capacity/SLO, burst profile, deployment sizing | **PARTIAL (Q13a).** Design target 13,333/s, prefetch 500, Writer sized for drain. **Volume closed at 115.2M/day (Q5a, 29 Aug 2026)** | **CLOSED 4 Sep 2026.** D5a **SIGNED** at RPO ≤ 5 min, RTO 1 h per partition / 24 h full cluster (DL-111). **Q13b answered: gateways JITTER** (DL-104) — expected steady state ~1,333/s, 13,333/s is configured headroom, and hardware is not sized down until the `ts.server mod 600` histogram confirms it |
| Late-arrival policy | **ENGINEERING AGREED 29 Aug 2026** — skip the write, meter `records_expired_on_arrival`, ACK, and alert when the counter is non-zero (ADR-003) | Writer, Phase 2. **SIGNED 4 Sep 2026 (D5b → DL-112); T-026 is unblocked.** Q15b (DL-106) confirms `ts.device` is genuine device time, so this is a real path |
| Clock skew — future `ts.device` | **CLOSED 29 Aug 2026 (Q14a, ADR-003 D9).** Rejected at Input beyond `stats.input.validation.max-clock-skew` (default `PT5M`): 400, field code `clock_skew_exceeded`, metered as `records_clock_skew_rejected{type}`. Cross-field, so it is code in Input — not a schema rule | Nothing. Implement in T-013 |
| Partition pre-creation mechanism | **CLOSED 29 Aug 2026 (ADR-003 D1).** Scheduled task in `stats-writer` behind the D3 advisory lock; `pg_partman` is never a dependency | Nothing. T-012 is unblocked |
| Topology declaration scope | **CLOSED 29 Aug 2026 (Q3a, ADR-002 §11).** Broker-connected modules only; `stats-api` gets no broker connection. T-032 is the enforcement | Nothing. Platform ack is a courtesy |

Use `PENDING APPROVAL` clearly in documentation, and **keep this table current** —
a stale `PENDING` row is not caution, it is a blocker nobody can lift. Never
silently convert a recommendation into a production behavior.

### 5.1 Already decided — do not reopen or re-ask

These were open in v1.0 and are now answered. Treating them as open wastes a
review cycle; contradicting them produces work that has to be thrown away.

| Decision | Answer |
|---|---|
| Database | PostgreSQL only. MongoDB is out of scope. |
| Language / JDK | Java 21 |
| Deployables | One Docker image per application module — three images. |
| Queue structure | One durable queue per stat type, `app_statsstore.<type>`, plus three retry tiers `app_statsstore.<type>.retry.<tier>` and a DLQ `app_statsstore.<type>.dlq`. The singular `app_statsstore` was a prefix. *(G1/G4)* |
| Exchange | `app_stats_input`, TopicExchange, routing key = stat type. |
| V1 types | All five, `omsstats` and `netstats` included. |
| OMS timestamps | Heimdal sends the canonical field. No transformation on our side. |
| `uid` | Unique; Heimdal does not deliver duplicates. Keep the `(ts_device, uid)` per-partition key as defence anyway, and meter every suppressed duplicate. |
| Tenant presence | Every record carries a tenant. |
| Isolation field | `tenant`, never `maintenant`. |
| Retention basis | `ts.device`, default 90 days, externally configurable. |
| HTTP success timing | Only after publisher confirm. |
| Broker unavailable | Non-2xx (503). No local buffering. Heimdal owns retry. |
| Error shape | RFC 9457 Problem Details — confirmed. |
| Dominant query pattern | `(tenant, clsbox, ts.device)`. |
| Scope | Dead-lettering, per-type metrics, and horizontal scaling are all in V1. |
| Validation | **Two gates.** Envelope at Input — failure is 400 and nothing is published. Payload at the Writer — failure is a **dead-letter**, never a 400. *(ADR-007 §5)* |
| `clsbox` case | Stored in **one canonical case**: the Writer uppercases, `CHECK (clsbox ~ '^[0-9A-F]{12}$')` backs it at the table, the schema still accepts either case, and the API filters `clsbox = upper(:clsbox)`. Every new type's table carries the CHECK. *(DL-115)* |
| Unmapped `data` members | Preserved in a nullable `data_extra jsonb` on every typed table, metered as `records_unmapped_data_field{type}`. *(DL-095)* |
| `lorastats` dialects | All three known dialects are accepted and normalised to one row; detection is **structural, never `version`**; the enumeration is closed at three. **Decided (DL-105), not yet built (T-063)** — today only bare/integer validates. |
| Writer logging | No throwable ever reaches a `stats-writer` log line. `SafeFailure` gives exception **type names** plus a validated `SQLState` — no message, no stack. Enforced by `WriterLogsCarryNoThrowableTest`. *(DL-124)* |

### 5.2 Ours to decide — decide, record an ADR, do not escalate

The business explicitly returned these to engineering. Leaving them undecided is
not caution; it is an unwritten ADR that the first line of code will settle by
accident.

| Ref | Decision | Position to ratify |
|---|---|---|
| D7 | Consumer ACK timing | Manual ACK after the successful PostgreSQL transaction |
| D11 | Individual versus batched writes | **Amended 18 Aug 2026 (G3).** Target is bounded micro-batching (100–500 records or 50–200 ms) with single-record fallback, which preserves per-record DLQ precision. Build individual inserts first behind a batch-shaped interface — the write method must accept a list of records and attribute a failure to a single record even when that list holds one element |
| D12 | Invalid filter combinations | 400 on an inverted range; document the maximum query window |
| D13 | Health-check contents | Separate liveness/readiness; readiness probes only that module's real dependencies |
| D1 | Log format | Structured JSON to stdout, `uid` as correlation ID |
| D14 | Unknown fields *(was mislabelled `D6`; answer-set `D6` is byte-identical publication — CLAUDE.md §8)* | Accept and preserve unknown `data` fields; reject invalid envelope fields |

## 6. PostgreSQL implementation rules

PostgreSQL is the only V1 persistence technology. Use PostgreSQL-compatible Spring Data/JDBC/JPA conventions only after the approved table/partition/index design exists. Use versioned migrations for every schema change. Test persistence against a real PostgreSQL Testcontainer. Do not introduce MongoDB drivers, `MongoRepository`, TTL-index code, Mongo Compose services, `_id` semantics, or Mongo-specific documentation.

The dominant query pattern is `(tenant, clsbox, ts.device)`. PostgreSQL indexes, partitions, and API queries must support that pattern without JSON path scans. Retention is based on `ts.device`; the retention strategy must be explicitly selected for PostgreSQL because the previous MongoDB TTL approach is no longer applicable.

## 7. Code structure rules

1. Use constructor injection. Do not use field injection.
2. Keep controllers thin: parse/authenticate/validate/delegate/map response only.
3. Keep application behavior behind interfaces where an implementation changes by stat type or storage strategy.
4. Each module may depend on `stats-common`; no application module may depend directly on another application module.
5. Read configuration from `application.properties` through typed configuration properties. Do not hard-code operational values or secrets.
6. Treat config as immutable per process unless an approved live-reconfiguration design exists.
7. Use UTC internally and serialize API timestamps only as defined in the approved OpenAPI.
8. Make all pagination deterministic with an indexed sort and a unique tie-breaker.
9. Use RFC 9457 Problem Details unless the approved Datahub platform error convention differs.
10. Do not add a dependency without explaining why Spring Boot or the approved platform libraries cannot already provide the capability.

## 8. Logging, security, and data handling

**The control is the LEVEL, not the path** — `DEVELOPMENT.md` §7 is authoritative and this
section restates it (DL-102). V1 permits payload logging to the Java logger/Docker logs
**only at `DEBUG`, which is off in production**. At `INFO` and above, on *every* path
including failure, never a full payload. Never log access tokens, passwords, private keys,
or secrets at **any** level, `DEBUG` included; redact them if they appear in a payload.
Keep payload logging configurable and document its production level, retention, access,
and data-classification impact. Always include `uid`, stat type, tenant, `clsbox`, module,
event, and error code for correlation. Do not echo full payloads in HTTP error responses.

An earlier revision of this section read "V1 permits payload logging to the Java
logger/Docker logs" with no level qualification, which contradicted DL-080 and read as
though a payload could be logged at `ERROR`. It could not, and cannot.

The required correlation ID is `uid`, unless an approved replacement is introduced. Log
events must permit traceability across `input`, `writer`, and `api` modules. Payload
logging at `DEBUG` is an accepted V1 decision, but Docker log retention/access must be
explicitly configured because records can contain IMSI, cell identifiers, IP addresses and
device identifiers — see `docs/decisions/DATA-SENSITIVITY-INVENTORY.md` for which field is
which, and **D-12** for the policy, which is not yet decided.

A stack trace is not a payload and is not covered by the rule above, but it can carry
payload values — a PostgreSQL constraint violation prints the offending key in its
`DETAIL:` line. Log the exception type and message; do not log the driver's full detail
line on a write failure.

## 9. Required development workflow for Claude

For every task, follow this sequence.

1. **Inspect.** Read the required source documents and relevant existing code/tests.
2. **Plan.** State requirements, assumptions, files to change, tests, failure behavior, and decisions that block work.
3. **Quiz.** Before implementing, quiz the requester on the decisions the change depends on — the trap it can fall into, what breaks if it is reversed, and which requirement or ADR governs it. A wrong or unsure answer is **not** a failure to move past: explain it, then **ask the quiz again**. Implementation starts once the quiz is passed. *(DL-005)*
4. **Wait.** Do not edit until the user approves the plan, unless the request explicitly asks for a small, reversible documentation-only edit.
5. **Implement minimally.** Make the narrowest change that meets the named requirements.
6. **Test.** Run focused unit tests, then relevant integration/contract tests. Do not claim a test passed without running it.
7. **Review.** Explain the requirement mapping, tests run and results, unresolved risks, and documentation updates.

### Why the quiz step exists

Nine of the eleven ways this system can lose a record fail **silently**
(`claude/FLOW.md` §8). A query on `maintenant` passes every test written
against the supplied samples. A timestamp read as milliseconds writes 1970
with no error. A TTL on the DLQ deletes the last copy of a record and reports
success. Code review does not catch these, because the code looks correct —
only someone who already knows the trap catches them.

The quiz makes that knowledge a **precondition of the change** rather than a
hope. Ask about the specific hazard the change touches, not the general topic.

| If the change touches | Ask about |
|---|---|
| Any query, cursor, or count | `tenant` vs `maintenant`; why in-memory filtering is not equivalent |
| Timestamps | Unix **seconds**; `ts.device` vs `ts.server`; the retention consequence |
| The Input response path | Why a 2xx before publisher confirm is unrecoverable; why a confirm is not a route |
| Broker topology | `RESOURCE_LOCKED` on a mismatched declaration; why the DLQ takes no TTL |
| Writer ACK or retry | Commit-then-ACK; publish-confirm-then-ACK on the retry hand-off |
| Inserts or the key | `(ts_device, uid)` column order; why `tenant` is out of the key |
| Retention or partitions | Detach-and-drop vs row deletion; what happens if pre-creation fails |
| Pagination | Why the sort is fixed and always applied; why the cursor carries its filters |
| A new stat type | Why it must be additive; what `StatTypeFanOutTest` proves |

## 10. Mandatory tests

| Change type | Mandatory tests |
|---|---|
| New payload/schema | Valid sample, every missing mandatory field, wrong type, unknown stat type, time values in seconds, safe error response. |
| Messaging/persistence | Correct routing key and queue, durable declaration, publisher-confirm behavior, retry limit, per-type DLQ, duplicate/redelivery behavior, poison-message isolation between types, and correct table selection. |
| API/query/security | 401, 403, GET success, every non-GET is 405, tenant isolation with `tenant != maintenant`, filters, deterministic pagination, invalid query errors. |
| Retention/index | Correct table indexes, retention keyed on `ts.device`, boundary tests just inside and just outside the window, a late-arriving-record test, and no accidental data expiry. Verify against a real PostgreSQL Testcontainer, not a mock. |
| Failure path | Structured metadata log, metric, retry/dead-letter/error response, and a test that proves the path is not silent. |

## 11. Definition of done for a change

A change is complete only when it has an approved requirement/ADR basis, a traceable commit/PR description, focused tests, relevant contract/document updates, and no unresolved security or tenancy question. If a decision is missing, the correct result is a documented blocker—not a plausible implementation.
