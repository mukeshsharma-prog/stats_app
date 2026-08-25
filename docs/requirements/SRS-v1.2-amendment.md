# Stats App SRS v1.2 — Implementation Amendment

**Status:** `PENDING REQUIREMENTS APPROVAL — PostgreSQL and answered decisions incorporated`  
**Purpose:** Apply the answered decisions, PostgreSQL-only direction, and supplied `netstats.json` sample to the normative implementation requirements.

## 1. Scope and authoritative decisions

The V1 stat types are `lorastats`, `ltestats`, `sysstats`, `omsstats`, and `netstats`. `omsstats` is explicitly included in every queue, consumer, table, retention, metrics, and API type list.

The persistence technology is **PostgreSQL only**. MongoDB is not a V1 alternative and must not appear in the code, Docker Compose stack, migrations, dependencies, or documentation. Each application module produces its own Docker image: `stats-input`, `stats-writer`, and `stats-api`.

The platform baseline is Java 21 with **Spring Boot 3.5.16**, pinned as a single entry in `gradle/libs.versions.toml` (G6, 18 August 2026) because “greater than 3.5.x” is not a reproducible build version. The pin is **final, not provisional**: Q1a was answered on 18 August 2026 and there is no internal reference project, so CI, logging, secrets, and deployment conventions are defined by this project rather than inherited. See `docs/decisions/2B-ANSWERS-18AUG2026.md` §1 and ADR-001.

## 2. Common record contract

Every accepted record MUST contain:

| Field | Type / rule |
|---|---|
| `uid` | Non-empty string; business answer states it is unique. Store as its own PostgreSQL column. Uniqueness is enforced per partition by `PRIMARY KEY (ts_device, uid)` — see STA-21B. |
| `tenant` | Non-empty tenant identifier; every record has a tenant. |
| `maintenant` | Non-empty managing-tenant identifier; it is not the API isolation field. |
| `gateway` | Exactly 12 hexadecimal characters unless an approved upstream exception exists. |
| `type` | One of the five approved literal stat types. |
| `ts.processor` | Unix timestamp in integer seconds. Heimdal already sends the canonical field, so Stats App performs no timestamp transformation. |
| `ts.device` | Unix timestamp in integer seconds; this is the query and retention timestamp. |
| `data` | Object valid against the selected type schema. |

The published RabbitMQ message is byte-identical to the accepted input, subject only to serialization required by the HTTP-to-AMQP implementation. No OMS timestamp normalization is performed by Stats App.

## 3. `netstats` schema — strict, answered 18 August 2026

Q4a was answered on 18 August 2026 (`docs/decisions/2B-ANSWERS-18AUG2026.md` §2).
`docs/schemas/netstats-v1.schema.json` is **no longer a draft**; it is the strict,
normative contract.

| Rule | Decision |
|---|---|
| `eth0` and `wwan` | **Mandatory.** A gateway missing either is a malfunctioning gateway, and finding out at ingest beats finding out from a null column. |
| Additional interfaces (`eth1`, `ppp0`, …) | **Allowed**, validated against the same interface shape via `patternProperties`, so a new interface name never requires a schema change *(STA-14A)*. |
| `apipa` | **Optional** — forced by the evidence: ZC's own sample carries it on `eth0` and omits it on `wwan`. Requiring it would reject the only real record we have. |
| `tx` / `rx` counters | **Strings on the wire, `BIGINT` in the column.** The schema constrains them to digits only, so a non-numeric counter fails at ingest with a 400 rather than failing a cast at query time. |
| `gw`, `ip`, `mask`, `dns[]` | IPv4 dotted-quad. **IPv6 is not accepted** — it does not appear in the sample. Q4b would widen one `$def` and change no code. |
| `timespan` | Integer seconds, minimum 0. |

| ID | Priority | Requirement |
|---|---|---|
| STA-04C | MUST | The Writer MUST **range-check every `netstats` counter** against the signed `BIGINT` maximum (`9223372036854775807`) before conversion. The schema's digit pattern caps at 19 digits (`9999999999999999999`); the gap between the two is not expressible as a regex, and an unchecked overflow corrupts the value with no error anywhere. Fixture: `docs/schemas/fixtures/invalid/netstats-counter-overflow.json`. |

## 4. Input Module requirements

| ID | Priority | Requirement |
|---|---|---|
| STA-01A | MUST | Define the Heimdal HTTP path, content type, size limit, timeout, correlation behavior, and response matrix in the inbound contract. |
| STA-03A | MUST | Invalid messages return the approved RFC 9457 Problem Details response, with safe field names/reason codes and no token or offending sensitive value. The approved response shape is confirmed. |
| STA-04A | MUST | The Input Module waits for RabbitMQ publisher confirmation before returning 2xx. |
| STA-05A | MUST | Input authentication is a **configuration-selected filter**: `stats.input.auth.mode=none\|mtls\|oauth2`, with **no default in code** so a deployment that omits it fails to start. `mode=none` MUST log a `WARN` on every startup naming the endpoint as unauthenticated, and the application MUST refuse to start under the `prod` profile with `mode=none`. The mechanism itself (mTLS or OAuth2 client credentials, with issuer/audience/client identity and rotation) is **deferred by decision** pending Q8a-2 and must close before any shared deployment. *(Q8a, 18 Aug 2026; ADR-006)* |
| STA-06A | MUST | Input is stateless and has no local durable buffer. If RabbitMQ is unavailable, return a non-2xx response; 503 is the default implementation choice. Heimdal handles dead-letter/retry behavior when it receives non-2xx. |
| STA-06B | MUST | Request size, timeout, concurrency, and rate-limit settings are externally configurable. |

## 5. RabbitMQ requirements

| ID | Priority | Requirement |
|---|---|---|
| STA-10A | MUST | Create one durable queue per stat type using the approved `app_statsstore.<stat-type>` convention. A shared `app_statsstore` queue is not permitted. Retry and dead-letter queues follow the same prefix: `app_statsstore.<stat-type>.retry.<tier>` and `app_statsstore.<stat-type>.dlq` *(G1, 18 Aug 2026)*. |
| STA-10B | MUST | Bind each queue to the applicable type routing key so a poison message cannot block another type. |
| STA-11A | MUST | Per-type consumer concurrency, prefetch, retry delay, and database-protection settings are externally configurable. |
| STA-12A | MUST | Define retry count, delay/backoff, transient/permanent classification, retry tracking, DLQ name, DLQ retention, and authorized redrive. **Three** retry tiers per stat type; all delays and the maximum attempt count are externalised to `application.properties`, never hard-coded, with starting values of 30 s / 5 min / 30 min. Immediate requeue is never used for a persistent failure *(G4, 18 Aug 2026)*. |
| STA-12B | MUST | Exhausted failures are visible in a per-type DLQ, metric, log, and alert. |
| STA-13A | MUST | Exchange, queues, and bindings are durable; accepted messages are persistent. The current answer says every module declares topology; interpret this as every broker-connected module and confirm whether `stats-api` is intended to connect to RabbitMQ. Declarations must be identical and idempotent. |
| STA-14A | MUST | Adding a type adds only its schema/configuration, binding, queue, consumer, PostgreSQL table, indexes, API operation, fixtures, and metrics. |

The exchange is `app_stats_input` and its type is `TopicExchange`. The routing key equals the stat type. The topology declaration instruction requires clarification for the API module because the API is otherwise a read-only PostgreSQL consumer and has no broker role.

## 6. PostgreSQL Stats Writer requirements

| ID | Priority | Requirement |
|---|---|---|
| STA-20A | MUST | Each type has isolated consumer configuration and per-type observability. Three application Docker images are independently deployable. |
| STA-21A | MUST | Persist each type in its own PostgreSQL table: `lorastats`, `ltestats`, `sysstats`, `omsstats`, and `netstats`. No shared table may mix types. |
| STA-21B | MUST | Each table contains the common fields, type-specific data, `uid` as its own column, and versioned migrations. Uniqueness is enforced by a primary key on `(ts_device, uid)` — in that column order — scoped **per partition, not globally**, because PostgreSQL requires a partitioned table's unique index to include the partition key. This is sufficient for the redelivery and replay cases it defends against, since such a record carries an identical `ts_device`. Suppressed duplicate inserts MUST be metered. *(ADR-003, G2 — supersedes the global-unique-constraint wording of v1.2)* |
| STA-21C | MUST | Indexes must prioritize the dominant query pattern `(tenant, gateway, ts.device)` and include a deterministic unique tie-breaker for pagination. |
| STA-23A | MUST | Retention is externally configurable with a default of 90 days unless an approved per-type override exists. |
| STA-23B | MUST | Retention uses `ts.device`. Late-arriving records can therefore be near expiry on arrival; this accepted business rule must be documented and tested. |
| STA-23C | MUST | PostgreSQL retention uses an approved partition-drop/detach strategy or bounded, indexed deletion jobs. It must expose deletion lag and active retention metrics. MongoDB TTL logic is forbidden. |
| STA-25A | MUST | Acknowledge only after a successful PostgreSQL transaction. Use the `(ts_device, uid)` constraint of STA-21B with `ON CONFLICT DO NOTHING` for duplicate-safe handling, even though Heimdal states it does not intentionally deliver duplicates. A suppressed insert MUST increment the `duplicate` metric of STA-27A; it is never discarded silently. *(ADR-003)* |
| STA-26A | MUST | Document per-type consumer concurrency, prefetch, PostgreSQL connection limits, deployment replicas, and backlog/autoscaling strategy. |
| STA-27A | MUST | Metrics include accepted, validation failures, publish-confirm failures, consumed, persisted, duplicate, retry, DLQ, duration, main queue depth, DLQ depth, PostgreSQL errors, retention lag, and consumer availability per type. |

At the answered volume of 160,000 gateways every 10 minutes, the corrected average is **23,040,000 records per type per day**, **92,160,000 per day for four types**, or **115,200,000 per day for five types**. The corresponding steady rates are 266.67, 1,066.67, and 1,333.33 records per second. Synchronized gateway reporting may create a much higher burst and must be load-tested.

## 7. API requirements

| ID | Priority | Requirement |
|---|---|---|
| STA-30A | MUST | Define and approve the versioned OpenAPI 3.1 contract before implementing controllers. |
| STA-31A | MUST | Configure Keycloak issuer, client/resource, audience, role, and tenant claim. The engineering proposal is live in the dev realm (Q8b, 18 Aug 2026): realm `statsapp`, audience `stats-api`, read role **`stats.readOnly`** (not the bare `readOnly` placeholder of the v1.0 answer), tenant claim `tenant` as a **single-valued String**. Every value is a configuration property with **no default in code**, so ZC's confirmation (Q8b-2) is a config change. *(ADR-006)* |
| STA-32A | MUST | API routes are GET-only. POST, PUT, PATCH, and DELETE return 405 with no side effect. |
| STA-33A | MUST | Every approved type, including `omsstats` and `netstats`, is individually queryable. |
| STA-34A | MUST | Derive tenant only from the validated token. Do not accept a normal caller-controlled tenant query parameter/header/body. |
| STA-34B | MUST | Define time format, `ts.device` basis, range boundaries, gateway filter, invalid-range behavior, maximum query window, page size, and deterministic ordering. Dominant filters are tenant, gateway, and `ts.device`. |
| STA-35A | MUST | Apply tenant filtering in every PostgreSQL query, count, cursor, and future aggregate. Never filter across tenants in memory. |
| STA-35B | MUST | Test with `tenant != maintenant` and ensure errors do not reveal another tenant’s data. |
| STA-36A | MUST | Use stable cursor/keyset pagination for high-volume tables; ordering must be indexed and include a unique tie-breaker. |
| STA-37A | SHOULD | Publish OpenAPI examples, security documentation, errors, limits, and compatibility guidance. |
| STA-38A | MUST | Breaking API changes require a new version or approved migration. |

## 8. Operational and security requirements

| ID | Priority | Requirement |
|---|---|---|
| NFR-01 | MUST | Separate liveness and readiness probes. Readiness reflects the dependencies required by each service. |
| NFR-02 | MUST | Java logger output goes to Docker stdout/stderr. Structured JSON is recommended for searchable fields but is an engineering choice for V1. |
| NFR-03 | MUST | The business answer permits payload logging. Implement configurable payload logging, redact tokens/secrets, document that payloads can contain IMSI/device identifiers, and make production log retention/access explicit. |
| NFR-04 | MUST | Define and test capacity, latency, availability, RTO, RPO, maximum backlog, replay, and PostgreSQL retention objectives. |
| NFR-05 | MUST | Every expected failure path has a metric, safe log, and runbook. |
| NFR-06 | MUST | Rolling deployment and graceful shutdown do not silently lose accepted messages. |

## 9. Traceability

Every code change cites its `STA-*`/`NFR-*` requirement and relevant ADR. Every type has positive, missing-field, wrong-type, unknown-type, tenant-isolation, retry/DLQ, PostgreSQL persistence, retention, pagination, and observability tests.
