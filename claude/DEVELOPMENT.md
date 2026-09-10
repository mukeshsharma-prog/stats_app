# DEVELOPMENT.md

How to build, run, test, and contribute to the Stats App.

> **Version 1.2 — 16 August 2026.** Rewritten for **PostgreSQL**. The v1.0 version
> of this file documented a MongoDB stack and said retention keys on
> `ts.server`; both are wrong. Retention keys on **`ts.device`**.
>
> **Status:** the remaining `TBC` items are listed in §11 and in `CLAUDE.md` §7.
> Where a section says TBC, do not guess; raise it.

---

## 1. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | **21** | Answered (Q1). Not 17. |
| Gradle | Wrapper (`./gradlew`) | Do not install globally |
| Docker + Compose | Recent | For local PostgreSQL, RabbitMQ, Keycloak |
| IDE | IntelliJ IDEA recommended | Best Gradle + Spring support |
| `psql` | 16+ client | Optional but useful for poking at the database |

Spring Boot is pinned at **3.5.16** in `gradle/libs.versions.toml` (G6, 18 Aug
2026), and final — Q1a closed the same day. Module build files never declare their
own versions — if ZC overrides the version it is a one-line change. The internal
reference project for CI, logging, secrets, and deployment conventions is still
outstanding; see §11.

---

## 2. Repository layout

Documentation is already in place. The `build.gradle` / module directories below
are created in Phase 1 — see `PROJECT_STATUS_AND_PLAN.md`.

```
stats_app/                          # repository root — everything lives here
├── README.md                       # index — start here
├── CLAUDE.md                       # pointer only — imports claude/CLAUDE.md so it auto-loads
│
├── claude/                         # working documents — all of them live here
│   ├── CLAUDE.md                   # project context, non-negotiable rules, traps
│   ├── IMPLEMENTATION_RULES.md     # detailed rules + required working sequence
│   ├── PROJECT_STATUS_AND_PLAN.md  # status, open items by owner, phased plan
│   ├── DEVELOPMENT.md              # this file
│   ├── SKILLS.md                   # repeatable procedures
│   └── CODE_START_CHECKLIST.md     # stop/go decision register
│
├── docs/
│   ├── requirements/               # SRS / BRD / ARB v1.2 amendments
│   ├── decisions/                  # answer reconciliation, PostgreSQL baseline
│   ├── architecture/
│   │   ├── adr/                    # ADR-001 … ADR-006
│   │   └── Stats_App_Flow.png
│   ├── contracts/                  # Heimdal input, AMQP topology, OpenAPI design
│   ├── schemas/                    # JSON Schema catalogue + fixtures
│   ├── prompts/                    # prompt templates for this repo
│   ├── setup/                      # IDE setup
│   └── source/                     # historical .docx — provenance only
│
│   ── created in Phase 1 ─────────────────────────────────────────
├── build.gradle(.kts)              # root build, shared plugin + version config
├── settings.gradle(.kts)           # module registration
├── gradle/libs.versions.toml       # version catalog — single source of dep versions
│
├── stats-common/                   # shared domain types, DTOs, envelope model,
│                                   # stat-type enum, AMQP topology constants
├── stats-input/                    # Module 1 — HTTP POST → AMQP
│   └── src/main/resources/application.properties
├── stats-writer/                   # Module 2 — AMQP → PostgreSQL
│   ├── src/main/resources/db/migration/   # versioned migrations
│   └── src/main/resources/application.properties
├── stats-api/                      # Module 3 — read-only REST
│   └── src/main/resources/application.properties
│
└── docker/
    └── docker-compose.yml          # PostgreSQL, RabbitMQ, Keycloak for local dev
```

The `openapi.yaml` we owe ZC goes in `docs/contracts/` alongside its design input.

Each application module builds its own Docker image — three images, answered (Q2).

### Why `stats-common`
The envelope (`uid`, `tenant`, `maintenant`, `clsbox`, `type`, `ts`) is identical
across all three modules, as are the exchange/queue/routing-key names. Defining
them once prevents drift, and — for the topology constants specifically — prevents
the `RESOURCE_LOCKED` failure that happens when two modules declare the same
RabbitMQ resource with different arguments. Put **only** genuinely shared things
here — resist making it a dumping ground.

### Module dependency rules

```
stats-input  → stats-common
stats-writer → stats-common
stats-api    → stats-common
```

**Never** module-to-module. `stats-api` must not depend on `stats-writer`. They
communicate only through the database and the broker. If you find yourself wanting
a direct dependency, the boundary is wrong.

`stats-api` has **no RabbitMQ dependency at all** — it is a read-only PostgreSQL
consumer. Q3a closed on 29 Aug 2026: only **broker-connected** modules declare topology,
and the API is not one of them (ADR-002 §11).

---

## 3. Local setup

### 3.1 Start infrastructure

```bash
cd docker
docker compose up -d
```

Brings up:

| Service | Port | Notes |
|---|---|---|
| PostgreSQL | 5432 | `statsdb` / `stats` / `stats` locally |
| RabbitMQ | 5672 / 15672 | Management UI on 15672, `guest`/`guest` |
| Keycloak | 8080 | Dev mode, realm imported at startup |

### 3.2 Verify

```bash
docker compose ps                                   # all healthy
curl http://localhost:15672                         # RabbitMQ management UI
psql -h localhost -U stats -d statsdb -c "select 1"
```

### 3.3 Run the modules

```bash
./gradlew :stats-input:bootRun
./gradlew :stats-writer:bootRun
./gradlew :stats-api:bootRun
```

Run in separate terminals. `stats-writer` should be running before you send
traffic through `stats-input`, or messages will sit in the queue — which is
correct behaviour, the buffer working as designed.

`stats-writer` applies its migrations on startup, so run it once before expecting
tables to exist.

### 3.4 Smoke test

```bash
# 1. Send a record.
#    approved-types.json is a map keyed by stat type, so extract one record.
jq '.lorastats' docs/schemas/fixtures/valid/approved-types.json > /tmp/lorastats.json
curl -X POST http://localhost:8081/stats \
  -H "Content-Type: application/json" \
  -d @/tmp/lorastats.json

# 2. Confirm it was consumed (queue should return to 0)
#    → http://localhost:15672 → Queues → app_statsstore.lorastats

# 3. Confirm it was stored
psql -h localhost -U stats -d statsdb -c "select count(*) from lorastats"

# 4. Read it back (needs a token — see §6)
curl http://localhost:8083/v1/stats/lorastats \
  -H "Authorization: Bearer $TOKEN"
```

If step 4 fails but 1–3 pass, the problem is auth, not the pipeline.

A useful negative smoke test: stop RabbitMQ and repeat step 1. You must get a
**503**, not a 200 — the Input module returns success only after publisher
confirm.

---

## 4. Configuration

All configuration lives in `application.properties`, read through typed
configuration properties. **Nothing** that varies by environment belongs in code.

### Shared
```properties
spring.application.name=stats-<module>
```

### Module 1 — Input
```properties
server.port=8081
spring.rabbitmq.host=${RABBITMQ_HOST:localhost}
spring.rabbitmq.port=${RABBITMQ_PORT:5672}

# Publisher confirms — STA-04A. A 2xx is only returned after the broker confirms.
spring.rabbitmq.publisher-confirm-type=correlated
stats.amqp.confirm-timeout=5s

# mandatory + a ReturnsCallback — ADR-002 §9. A confirm says the BROKER accepted the
# message, not that a queue wanted it; a returned message is a FAILED acceptance.
spring.rabbitmq.template.mandatory=true

# Bound AND checked against AmqpTopology.INPUT_EXCHANGE — any other value refuses to
# start (DL-118). Renamed from stats.exchange.name, which nothing read.
stats.amqp.exchange=app_stats_input

# Clock skew — Q14a, decided 29 Aug 2026 (ADR-003 D9). A ts.device more than this
# ahead of ts.server is a 400 with the field code clock_skew_exceeded, metered as
# records_clock_skew_rejected{type}. Cross-field, so it cannot live in the schema.
stats.input.validation.max-clock-skew=PT5M

# Request limits — STA-06B, T-018 (DL-121). ENGINEERING DEFAULTS, not ZC's answer:
# D-03 fixes no numbers, and an unbounded ingest endpoint is wrong at every value it
# can return. Over the limit is 413; over the rate is 429 with Retry-After. The rate
# is PER INSTANCE — the cluster ceiling is this times the replica count.
stats.input.limits.max-request-bytes=256KB
stats.input.limits.max-requests-per-second=4000

# The read timeout is the container's; a stalled TCP connection is not something
# application code can observe.
server.tomcat.connection-timeout=10s
```

### Module 2 — Stats Writer
```properties
server.port=8082
# ACK only after the PostgreSQL transaction commits — D7, STA-25A.
spring.rabbitmq.listener.simple.acknowledge-mode=manual

# The Writer publishes exactly one thing: the retry or dead-letter copy of a delivery
# it could not persist. That copy is CONFIRMED before the original is ACKed (ADR-005,
# DL-122) — the reverse order loses the record with everything reporting success.
spring.rabbitmq.publisher-confirm-type=correlated
spring.rabbitmq.template.mandatory=true
stats.amqp.confirm-timeout=5s

# Three explicit tiers, never a multiplier, every value externalised (G1/G4). One
# attempt per tier, so a fourth failure retires the record to <type>.dlq. There is
# deliberately NO dlq TTL property and no code that could read one (ADR-002 §12).
stats.amqp.retry.tier1-delay=PT30S
stats.amqp.retry.tier2-delay=PT5M
stats.amqp.retry.tier3-delay=PT30M
stats.amqp.max-retries=3
spring.datasource.url=${POSTGRES_URL:jdbc:postgresql://localhost:5432/statsdb}
spring.datasource.username=${POSTGRES_USER:stats}
spring.datasource.password=${POSTGRES_PASSWORD:stats}

# Retention — STA-23A/STA-23B. Default 90 days, overridable per type.
# Keys on ts.device, NOT ts.server. See the caveat below.
# NO DEFAULT IN CODE: retention deletes data, so an unconfigured deployment must
# fail to start rather than adopt a window somebody guessed (DL-132).
stats.retention.default-days=90
# Per-type override. Validated against StatType.wireName() at startup, so a typo
# FAILS THE CONTEXT instead of silently reverting that type to the default. This
# replaced `stats.retention.<type>-days`, which never bound to anything (DL-132).
# stats.retention.days-by-type.sysstats=30
# ADR-003 D3 — stagger the five types rather than firing them together. The offset
# is derived from the TYPE, so it separates five deployments in production and five
# passes in `all` mode with one mechanism. PT0S disables it.
stats.retention.stagger=PT30S

# Partitioning is the retention mechanism — ADR-003, built by T-025 (DL-132).
# DETACH PARTITION ... CONCURRENTLY then DROP TABLE, once daily per type, behind
# pg_try_advisory_lock. It never deletes a row. One scheduled entry point runs
# pre-creation and THEN retention, so tomorrow's partition exists before
# yesterday's is dropped.
stats.retention.partition-granularity=daily
stats.retention.pre-create-days=7
stats.retention.schedule=0 30 3 * * *        # Spring 6-field cron, off-peak
stats.retention.pre-create-on-startup=true

# The four batched-DELETE fallback keys that used to sit here are GONE (DL-130,
# 8 Sep 2026). They configured the path to take "if partitioning is not approved",
# and it WAS approved — so they tuned a design the project decided against, read by
# no code, while still reading as live operational settings.

# Retry tiers and DLQ — STA-12A, rule 5, G1/G4. THREE tiers with fixed delays,
# all externalised here and NEVER hard-coded. There is no multiplier: the tiers
# are explicit, not derived from a backoff curve. Immediate requeue is never used
# for a persistent failure — that is a hot loop against a failing database.
stats.amqp.retry.tier1-delay=PT30S
stats.amqp.retry.tier2-delay=PT5M
stats.amqp.retry.tier3-delay=PT30M
stats.amqp.max-retries=3
# There is deliberately NO DLQ retention property. The DLQ never expires messages:
# a TTL'd dead-letter queue is silent data loss (ADR-002 §12, 29 Aug 2026). Bounding
# broker disk is an alerting and disposition problem — alert on DLQ depth > 0 and on
# oldest-message age, dispose manually within 14 days.

# Per-type consumer controls — STA-11A. Prefetch starts at 500 (Q13a, 2B §5):
# above the 100-500 micro-batch band so a batch can always be filled, low enough
# that one consumer cannot monopolise a synchronised burst. Tune by measurement.
stats.consumer.lorastats.concurrency=1
stats.consumer.lorastats.prefetch=500
```

> **Retention caveat (STA-23B):** retention keys on **`ts.device`** — the device's
> own timestamp, not the time we processed it. A gateway that was offline for
> three weeks delivers records that are already 21 days into their 90-day window,
> and a gateway offline for 91 days delivers records that expire almost
> immediately. This is the **answered and accepted business rule**. Do not
> "correct" it to `ts.server`. Document it, and cover it with a late-arrival
> boundary test.

### Module 3 — Stats API
```properties
server.port=8083
spring.datasource.url=${POSTGRES_URL:jdbc:postgresql://localhost:5432/statsdb}
spring.datasource.username=${POSTGRES_RO_USER:stats_ro}
spring.datasource.password=${POSTGRES_RO_PASSWORD:stats_ro}

# Keycloak — ADR-006 is ACCEPTED. These values are the engineering proposal that
# is live in the dev realm and awaiting ZC confirmation (Q8b-2). Give them NO
# defaults in code: an unset value must stop the module starting, never fall back
# to a guessed security posture.
spring.security.oauth2.resourceserver.jwt.issuer-uri=${KEYCLOAK_ISSUER}
stats.security.audience=${STATS_API_AUDIENCE}
stats.security.read-role=${STATS_READ_ONLY_ROLE}
stats.security.tenant-claim=${STATS_TENANT_CLAIM}

stats.api.default-page-size=100
stats.api.max-page-size=1000
stats.api.max-query-window-days=TBC
```

Give the API a **read-only database user**. The "only Module 2 writes" rule should
be enforced by database grants, not just by convention — that way a mistake in
code fails loudly instead of silently writing.

### Rules
- Secrets come from the environment, never from a committed file
- Every property gets a comment naming its `STA-nn` requirement where one applies
- No magic numbers in code — if it might change, it is a property
- Config is immutable per process unless an approved live-reconfiguration design exists

---

## 5. Building and testing

```bash
./gradlew build                       # everything
./gradlew :stats-writer:build         # one module
./gradlew test                        # unit tests
./gradlew integrationTest             # integration (needs Docker)
./gradlew :stats-api:test --tests "*TenantIsolation*"
```

### Test layers

| Layer | Scope | Infrastructure |
|---|---|---|
| Unit | Validation, mapping, business logic | None — mocks only |
| Integration | Repository, messaging, controllers | Testcontainers |
| Contract | Payload schema conformance | Fixtures in `docs/schemas/fixtures/` |
| Load | Throughput, backpressure, recovery | Dedicated environment |

Use **Testcontainers** for PostgreSQL and RabbitMQ in integration tests — real
behaviour, disposable, no shared local state. Persistence, migrations, indexes,
and retention must be verified against a real PostgreSQL container. An in-memory
database will not reproduce partition pruning, index behaviour, or constraint
semantics, so it is not an acceptable substitute here.

### Coverage expectations
Not a percentage target. These specifically must be covered:

- Every validation rule, positive and negative *(STA-03A)*
- **Tenant isolation, with `tenant ≠ maintenant` fixtures** *(STA-35A, STA-35B)*
- Publisher-confirm timing: no 2xx before confirm; 503 when the broker is down *(STA-04A, STA-06A)*
- Dead-letter path on write failure, per type *(STA-12A, STA-25A)*
- Poison-message isolation: a bad message in one type does not stall another *(STA-10B)*
- Duplicate `uid` redelivery is a harmless no-op *(STA-25A)*
- Retention boundary — just inside and just outside, plus a late-arriving record *(STA-23A, STA-23B)*
- Every non-GET verb rejected on every endpoint *(STA-32A)*
- Deterministic pagination under concurrent writes *(STA-36A)*

Full checklist: `SKILLS.md` Skill 3.

---

## 6. Local Keycloak

```bash
# Get a token for local testing
curl -X POST http://localhost:8080/realms/statsapp/protocol/openid-connect/token \
  -d "client_id=stats-api" \
  -d "username=testuser" \
  -d "password=testpass" \
  -d "grant_type=password" | jq -r .access_token
```

The dev realm ships five users, and each one is a test rather than filler:

| User | Proves |
|---|---|
| `alpha-reader` | Reads tenant-alpha, and only tenant-alpha |
| `beta-reader` | The other half of the cross-tenant isolation pair |
| `no-role` | An authenticated caller without `stats.readOnly` gets **403** |
| `no-tenant` | A token with **no** `tenant` claim gets 403 — absent never means "all tenants" |
| `empty-tenant` | A token with an **empty** `tenant` claim gets 403 too. Separate from `no-tenant` on purpose: a naive `claim != null` check passes this one and then filters on an empty string |

> Realm, clients, roles and the tenant claim are **our proposal, live in the dev
> realm** (Q8b answered 18 Aug 2026 by building it rather than waiting):
> realm `statsapp`; clients `stats-api` / `stats-input` / `stats-dev-cli`;
> audience `stats-api`; read role **`stats.readOnly`**; ingest role
> `stats.ingest`; tenant claim **`tenant`, single-valued String**.
>
> `stats.readOnly` is the real name — **not** the bare `readOnly` placeholder from
> the v1.0 answer. ZC's confirmation (Q8b-2) is outstanding, but because every one
> of these is a configuration property with no default in code, confirming them is
> a config change. Do not hard-code any of them.
>
> The claim is single-valued on purpose: a multi-valued claim turns isolation into
> `tenant IN (...)`, and one misconfigured user then reads several tenants with
> every test still green.

---

## 7. Code conventions

### Naming
| Thing | Convention | Example |
|---|---|---|
| Table | stat type name, lowercase | `lorastats` |
| Routing key | stat type name | `lorastats` |
| Main queue | `app_statsstore.<type>` | `app_statsstore.lorastats` |
| Retry queues (3 tiers) | `app_statsstore.<type>.retry.<tier>` | `app_statsstore.lorastats.retry.1` |
| Dead-letter queue | `app_statsstore.<type>.dlq` | `app_statsstore.lorastats.dlq` |
| Dead-letter exchange | `app_stats_dlx` | — |
| Config property | `stats.<area>.<name>` | `stats.retention.default-days` |
| Endpoint | `/v{n}/stats/{type}` | `/v1/stats/lorastats` |

### Structure
- Constructor injection only. No field injection.
- Controllers stay thin — validate, delegate, map. No business logic.
- Repository interface per stat type, so a new type is an additive change.
- Retention behind its own abstraction, separate from the write path.
- Every schema change is a **versioned migration**. No `ddl-auto` beyond `validate`.
- UTC internally; serialize API timestamps only as the approved OpenAPI defines.
- All pagination deterministic: indexed sort plus a unique tie-breaker.

### Baseline table and index shape

Per **ADR-003**: PostgreSQL 17, daily range partitions on `ts_device`, typed
columns for the four stable types, `jsonb` only for the `netstats` interface map.

```sql
CREATE TABLE <stat_type> (
    uid            text        NOT NULL,
    tenant         text        NOT NULL,
    maintenant     text        NOT NULL,
    clsbox         text        NOT NULL,
    ts_server      timestamptz NOT NULL,
    ts_device      timestamptz NOT NULL,
    received_at    timestamptz NOT NULL DEFAULT now(),
    record_version smallint    NOT NULL,
    -- typed payload columns; bigint for counters, never integer
    <payload columns>,
    passthrough    jsonb,   -- ts.datastore / ts.converter / ts.expiresOn / status
    data_extra     jsonb,   -- unmapped `data` members, DL-095. NEVER omit this
    PRIMARY KEY (ts_device, uid),
    CONSTRAINT <stat_type>_clsbox_canonical CHECK (clsbox ~ '^[0-9A-F]{12}$')
) PARTITION BY RANGE (ts_device);

-- Created on the parent; PostgreSQL propagates it to every partition.
-- Dominant query pattern (STA-21C), with a unique tie-breaker for keyset paging
CREATE INDEX idx_<stat_type>_tenant_clsbox_device_uid
    ON <stat_type> (tenant, clsbox, ts_device DESC, uid DESC);

-- One partition per day, pre-created ahead of ingestion
CREATE TABLE <stat_type>_2026_08_16 PARTITION OF <stat_type>
    FOR VALUES FROM ('2026-08-16') TO ('2026-08-17');
```

Three things to know before you write a migration:

- **The primary key is `(ts_device, uid)`, not `uid`.** PostgreSQL requires a
  unique index on a partitioned table to include the partition key; a global
  unique index across partitions does not exist. `ts_device` leads so the key
  matches the partition key and the mandatory `(ts_device DESC, uid DESC)` sort
  (G2). Uniqueness is therefore scoped
  per partition. Redelivery still cannot duplicate, because a redelivered record
  is byte-identical and carries the same `ts_device`. See ADR-003.
- **There is no `retention_at` column.** The partition boundary *is* the
  retention mechanism; a column would be a second source of truth.
- **Counters are `bigint`.** The SRS says "integer", but these are monotonic
  counters over long device uptimes and `int4` overflow is silent corruption.
- **`data_extra jsonb` is not optional (DL-095).** The typed columns have nowhere to
  put an unmapped `data` member, Input preserves it byte-identically, and the Writer
  used to drop it at the `INSERT`. Omitting the column silently reopens that loss.
  Almost every row is `NULL`, so it does not reintroduce the key-name overhead
  ADR-003 D2 rejected. Meter a use as `records_unmapped_data_field{type}`.
- **The `clsbox` CHECK is not optional either (DL-115).** The Writer uppercases with
  `Locale.ROOT` before the `INSERT` and the constraint holds the invariant at the
  table, so the rule survives a future writer that forgets. The envelope schema keeps
  `^[0-9A-Fa-f]{12}$` on purpose: a lowercase record is **normalised, never 400'd**.
  And the API filter is `clsbox = upper(:clsbox)` — putting `upper()` on the column
  side silently stops the index being used.

The idempotent write is:

```sql
INSERT INTO <stat_type> (...) VALUES (...) ON CONFLICT (ts_device, uid) DO NOTHING;
```

Dominant filters — `tenant`, `clsbox`, `ts_device` — must never require a JSON
path scan.

### Behavioural defaults

These were open in v1.0. Most are now **answered** and are no longer proposals.

| Behaviour | Value | Status |
|---|---|---|
| Error response format | RFC 9457 Problem Details (`ProblemDetail`, native in Spring Boot 3) | **Confirmed** (D3) |
| HTTP 2xx timing | Only after broker publisher-confirm | **Confirmed** (D4) |
| RabbitMQ unreachable | Return 503; do **not** buffer locally | **Confirmed** (D5) |
| Published message | Identical to what was received; no transformation | **Confirmed** (D6) |
| `uid` storage | Its own PostgreSQL column; uniqueness is `PRIMARY KEY (ts_device, uid)` per partition | **Confirmed** (D8), amended by ADR-003/G2 |
| Retention timestamp | **`ts.device`** | **Confirmed** (D9) |
| Payload logging | Permitted, but configurable and with secrets redacted | **Confirmed** (D2) |
| Log destination | Java logger → Docker stdout; format is ours to choose | **Confirmed** (D1) |
| Message acknowledgement | After successful DB transaction, never before | Ours (D7) — ratify in ADR-005 |
| Write mode | Individual inserts first, behind a batch-shaped interface; bounded micro-batching (100–500 records or 50–200 ms) with single-record fallback is the target | Ours (D11, amended by G3) |
| `from` later than `to` | 400, not an empty result | Ours (D12) |
| Health checks | Separate liveness and readiness | Ours (D13) |
| Unexpected extra payload fields | Accept and preserve unknown `data` fields; reject invalid envelope fields; log at DEBUG | Ours (**D14**) — decide explicitly, never via a Jackson default |
| Consumer rate control | Prefetch count + concurrency, per type | Ours |
| Page size | `limit`, default 100, max 1000 | **Frozen** in `docs/contracts/openapi.yaml` |
| Sort order | Always applied, indexed, newest first, unique tie-breaker | Ours |
| API time format | **Unix epoch seconds**, in filters and in responses — not RFC 3339, not milliseconds. It is what Heimdal sends and what retention keys on, so a value never changes meaning between ingest and read | **Frozen** in `docs/contracts/openapi.yaml` |
| Topology declaration | Shared constants in `stats-common`; identical and idempotent; **broker-connected modules only** — Input and Writer, never the API | **Decided** (Q3a, ADR-002 §11, 29 Aug 2026) |

### Error handling
- Never swallow an exception. Handle, rethrow, or dead-letter.
- Log with context: stat type, `uid`, tenant. Never log the full payload at
  `INFO` — these records contain device identifiers.
- Validation failures → **400** using RFC 9457 Problem Details, naming every
  offending field and reason in one response, with `uid` echoed back for log
  correlation. Never echo the offending *value* — payloads carry device
  identifiers. An unrecognised stat type gets its own code
  (`unknown_stat_type`), not a generic field error. *(STA-03A)*

  ```json
  {
    "type": "https://<host>/errors/validation-failed",
    "title": "Validation failed",
    "status": 400,
    "detail": "2 fields failed validation",
    "instance": "/stats",
    "statType": "lorastats",
    "uid": "a3f1c8e2-...",
    "errors": [
      { "field": "ts.server",   "code": "missing",
        "message": "Mandatory field is absent" },
      { "field": "data.tx_success", "code": "invalid_type",
        "message": "Expected integer, got string" }
    ]
  }
  ```
  The Stats API reuses this same shape so the modules do not diverge.
- Broker unavailable or confirm timeout → **503**. Heimdal owns retry from there.
- Auth failures → **401** unauthenticated, **403** wrong role
- Cross-tenant access → return **empty results**, not 403. A 403 confirms the
  record exists, which is itself a leak.

### Logging
| Level | Use |
|---|---|
| ERROR | Something needs human attention |
| WARN | Handled but abnormal — retry, dead-letter, validation failure |
| INFO | Lifecycle: startup, config loaded, migration applied |
| DEBUG | Per-message detail, full payloads. Off in production. |

Never log secrets, tokens, private keys, or full payloads at `INFO` or above.

**Structured logging.** All three modules emit JSON to stdout with the same field
names, so one record can be traced end to end *(D1, NFR-02)*:

```
uid          — correlation ID across all three modules
statType     — lorastats | ltestats | sysstats | netstats | omsstats
tenant       — for filtering; never the full payload
clsbox       — source device. NOT `gateway`: ADR-007 §1 is one vocabulary end to
               end, and a log field is a field (CLAUDE.md §4)
module       — input | writer | api
event        — validation_failed | published | persisted | retried | dead_lettered
```

**What may be logged on failure** *(D2, NFR-03)*: the business permits payload
logging. Keep it **configurable**, redact tokens and secrets always, and default
full payloads to `DEBUG`. These records contain device identifiers (IMSI, MAC),
and Docker log retention does not share the database's 90-day policy — so log
retention and access must be configured explicitly, not inherited by accident.

**This section is the authoritative logging rule** for the whole repository, and the
control is the **level, not the path**: full payloads at `DEBUG` only, never at `INFO`
or above on *any* path, and never a token or secret at *any* level. DL-080 and
`IMPLEMENTATION_RULES.md` §8 were reconciled to it on 4 Sep 2026 (DL-102) — an earlier
revision of §8 permitted payload logging with no level qualification at all.

**A stack trace is not a payload and this rule does not cover it.** A PostgreSQL
constraint violation prints the offending key values in its `DETAIL:` line, so an
exception logged at `ERROR` can carry payload content that `DEBUG`-gating never sees.
Log the exception type and message, not the driver's detail line.

**Two stricter guards were ADOPTED on 4 September 2026** (DL-114). Both are reversible
config-level rules that `DATA-SENSITIVITY-INVENTORY.md` §7 offered as P1 and P2, and
neither needs a signature — they narrow what may be logged, so adopting them cannot
foreclose anything security later decides:

* **P1 — never log an `ltestats` or `netstats` payload at *any* level, `DEBUG` included.**
  Stricter than the rule above, for these two types only. They are the two that carry
  `imsi`, `lac`/`cid` and `ip`/`gw`/`dns`, and an `ltestats` payload in a log *is* an IMSI
  in Docker logs, under a log-retention policy that does not exist yet. It costs nothing
  today because neither type is built; adopting it before T-034 is the entire point.
* **P2 — on a write failure, log the exception type and message only, never the driver's
  `DETAIL:` line.** This is the paragraph above, now a decision rather than advice.

**Both are now ENFORCED IN THE BUILD, not only written down (T-066, DL-124, 5 Sep 2026).**
`SafeFailure.describe(failure)` is the only way `stats-writer` may put a failure in a log
line — it keeps the causal chain of exception *types* and the `SQLState`, and drops every
message and stack — and `WriterLogsCarryNoThrowableTest` fails the build if any
`stats-writer` main source hands a throwable to a logger.

**The correction that made the guard necessary.** The sentence that stood here on 4 Sep —
*"rules for code that does not exist yet: stats-writer has no ERROR or WARN path at all
until T-024 and T-057"* — **was already wrong when it was written.** `LorastatsBatchWriter`
had logged `LOGGER.error(…, failure)` on the INSERT failure path since T-021, which is the
precise path P2 names, and `PartitionMaintenance` had a second. T-024 then added two more
by copying the shape. Four sites, one of them older than the rule. Writing a rule down
before the code is worth doing and was not enough: what closed it was making the build
fail. Note also that the guards' scanned sources are declared as Gradle task inputs — until
5 Sep they were not, so an edit to `stats-writer` left `:stats-common:test` UP-TO-DATE and
every architecture guard silently did not run.

`stats-input` is deliberately out of scope: it logs broker exceptions, and an
`AmqpConnectException` carries a host and port rather than a record.
**P4 and P5 (masking `imsi`, and treating `lac`/`cid` as location data) are irreversible
and still need security's signature** — do not infer them from this section.

**Which fields are actually sensitive** is catalogued per field, per stat type, and per
sink in `docs/decisions/DATA-SENSITIVITY-INVENTORY.md`. The *policy* over them is **D-12**
and is not decided — do not infer one from this section.

**Log retention** *(D1)*: must exceed the realistic time-to-notice for a problem.
A rejected message exists nowhere else — the log line is the only record that it
ever arrived.

---

## 8. Git workflow

### Branches
```
feature/STA-21-lorastats-table
fix/dlq-retry-limit-not-applied
chore/pin-spring-boot-version
```

### Commits
Reference the requirement and the governing ADR:
```
feat(writer): add lorastats table with tenant/gateway/device index

Implements STA-21A (table per stat type) and supports STA-35A (tenant
separation) via a compound index on (tenant, clsbox, ts_device, uid).

Refs: STA-21A, STA-21C, STA-35A, ADR-003
```

### Pull requests
- [ ] Requirement IDs and governing ADR named in the description
- [ ] Tests added — see SKILLS.md Skill 3
- [ ] **If it touches queries, controllers, or security: SKILLS.md Skill 2 run**
- [ ] Migration added and reversible, if the schema changed
- [ ] OpenAPI spec updated if the API changed *(STA-37A)*
- [ ] No new dependency without justification
- [ ] No hard-coded config
- [ ] No MongoDB, no `_id`, no TTL index, no "collection"
- [ ] No main-source file names an individual stat type — `StatTypeFanOutTest` fails on
      the enum constant or the quoted wire literal outside its documented whitelist
      *(STA-22, trap 15)*
- [ ] Queue arguments come from `AmqpTopology`'s maps, never hand-assembled
      *(ADR-002 §12, trap 17)*

---

## 9. Definition of done

A stat type is done when:

- [ ] Valid records are accepted and stored in their own table
- [ ] Invalid records return 400 with Problem Details, and nothing is published *(STA-03A)*
- [ ] 2xx is returned only after publisher confirm; broker down returns 503 *(STA-04A, STA-06A)*
- [ ] Forced write failure lands in that type's DLQ *(STA-12A)*
- [ ] A duplicate `uid` redelivery is a harmless no-op *(STA-25A)*
- [ ] A poison message in this type does not stall any other type *(STA-10B)*
- [ ] Data is readable via GET, tenant-scoped *(STA-33A, STA-35A)*
- [ ] Non-GET verbs return 405 *(STA-32A)*
- [ ] Retention configured on `ts.device` and boundary-verified *(STA-23A, STA-23B)*
- [ ] Pagination works and is stable *(STA-36A)*
- [ ] OpenAPI spec updated *(STA-37A)*
- [ ] Metrics exposed, including queue depth and DLQ depth *(STA-27A)*
- [ ] **No existing stat type's code was modified** *(STA-22)*

---

## 10. Troubleshooting

| Symptom | Likely cause |
|---|---|
| Messages published but queue stays empty | Routing key does not match the binding |
| Queue grows, never drains | Consumer down, or PostgreSQL unreachable — see SKILLS.md Skill 4 |
| Same message retried forever | Retry limit not configured; DLQ not bound |
| `RESOURCE_LOCKED` / declaration error on startup | Two modules declaring the same topology with different arguments — use the shared constants in `stats-common` |
| API returns 401 with a valid-looking token | Issuer URI mismatch, or expired token |
| API returns empty for data that exists | Tenant filter — check `tenant` vs `maintenant` |
| Timestamps show as 1970 | Seconds treated as milliseconds |
| Data expires far too early | Expected for late-arriving records — retention keys on `ts.device` by design. Confirm it is not a units bug before calling it a defect. |
| Nothing ever expires | Retention job not scheduled, or every replica lost the advisory-lock race and skipped the run |
| Insert fails with "no partition of relation found" | Partition pre-creation fell behind, or the record's `ts_device` predates the oldest live partition — see the late-arrival policy in ADR-003 |
| Retention job runs but the table keeps growing | Unbounded `DELETE` causing bloat — needs bounded batches or partition drop |
| Deletes get slower over time | Index/table bloat; check `autovacuum` and consider partitioning |
| Same record appears on two pages | Pagination without a deterministic sort and unique tie-breaker |
| Query is slow despite an index | Filter is hitting a JSON path scan instead of an indexed column |
| Heimdal reports success but data is missing | 2xx returned before publisher-confirm; message lost in the gap |
| Duplicate key violation on `uid` | Expected on redelivery — must be handled as an idempotent no-op, not a DLQ |
| Writer writes but API cannot see it | API pointed at a different database, or its read-only grants are missing |

---

## 11. Before you start coding

The v1.0 blockers are closed, and so are the six §2b items of 18 August 2026
(`docs/decisions/2B-ANSWERS-18AUG2026.md`). What follows is what genuinely remains.

### Blocks the build

Nothing. Q1a is closed — there is no internal reference repo, Spring Boot 3.5.16 is
pinned, and the conventions it would have supplied are defined in ADR-001.

### Risk accepted, not answered — re-read before any shared deployment

| Ref | State |
|---|---|
| **Q8a-2** | Inbound Heimdal auth is **deferred by decision**. Until ZC answers, anything that can reach the Input port can write. `stats.input.auth.mode` has no default in code, `none` logs a `WARN` on every startup, and a test refuses to start under `prod`. Fine on a dev stack, nowhere else. |
| ~~**D5a**~~ | **CLOSED 4 Sep 2026 — SIGNED (DL-111):** **RPO ≤ 5 min** via continuous WAL archiving; **RTO 1 h per partition, 24 h full cluster**. RPO is the tight one because a record we 200'd and then lost is unrecoverable — Heimdal only retries on a non-2xx. Signing obliges two things: continuous WAL archiving must be configured **and its lag alerted** (a stalled archiver silently turns RPO into *"however long ago it stopped"*), and T-051 must be **timed** against the 1-hour figure, not merely completed. |

### Blocks a specific area

| Ref | Blocks |
|---|---|
| **Q8b-2** | ZC's confirmation of the Keycloak realm/client/issuer/audience/role/claim. The proposal is live in the dev realm and every value is a property with no default, so confirming it is a config change — it blocks *hardening sign-off*, not development. |
| ~~**Q13b**~~ | **ANSWERED 4 Sep 2026 (DL-104): gateways JITTER** across the cycle. Expected steady state ~1,333/s; DL-082's 13,333/s becomes **configured headroom**, and hardware is not sized down until a `ts.server mod 600` histogram over the first 24 h of ingest confirms it. **One reading inverts:** a cyclic queue-depth spike used to be the buffer working — under jitter it is evidence the answer is wrong. |
| ~~**Q13c**~~ | **ANSWERED 4 Sep 2026 (DL-109):** Heimdal agrees the 2× cap. **Not one defence changes** — an agreement is a commitment, not a control; 503 backpressure, bounded prefetch and T-018's rate limit stay. T-049 unblocked. |
| ~~**Q4b**~~ | **ANSWERED 4 Sep 2026 (DL-110): no IPv6, for now.** The schema stays IPv4-only and strict; the IPv4 `$def` failure emits a **distinct metered per-field code**, and a non-zero count on it reopens Q4b. |
| ~~**D5b**~~ | **SIGNED 4 Sep 2026 (DL-112), BUILT 10 Sep 2026 (T-026, DL-133):** skip the write, meter `records_expired_on_arrival{type}`, `WARN`, ACK — **never dead-letter**. Still a deliberate data-loss-by-design path — the signature is what makes it a decision rather than a defect. **`alert when non-zero` is NOT built**: that is T-050, and it is the half that separates a systemic gateway backlog from a one-off. |

### Decided 29 August 2026 — do not reopen

| Ref | Decision |
|---|---|
| **Q14a** | Clock skew — **rejected at Input** when `ts.device` exceeds `ts.server` by more than `stats.input.validation.max-clock-skew` (default `PT5M`). 400, field code `clock_skew_exceeded`, metered as `records_clock_skew_rejected{type}`. Cross-field, so it is a code check in Input; the five `edge/<type>-clock-skew-future.json` fixtures stay schema-valid and carry `$expect: "input-reject"` (ADR-003 D9). |
| **Q3a** | Topology is declared by **broker-connected modules only** — Input and Writer. `stats-api` never gets a RabbitMQ connection. T-032 is the enforcement (ADR-002 §11). |
| **Q5a** | **115.2M records/day** across five types. The internal "around 10 million" is withdrawn. It never blocked freezing the schema — every schema decision is identical at both figures. |
| **DLQ retention** | **No TTL, ever.** Alert on depth > 0 and oldest-message age; manual disposition within 14 days; redrive by an authorised ops role, per type, at ≤ 2× nominal, root cause first (ADR-002 §12). |
| **`pg_partman`** | Never a dependency. Partition pre-creation is a scheduled task in `stats-writer` behind the D3 advisory lock (ADR-003 D1). |

### Not blocked — start now

`stats-common` envelope model and topology constants; the `lorastats` vertical
slice end to end; the Input endpoint including its auth *filter seam*; the Writer
with partitioning, retention and metered duplicate suppression; and the API
against the drafted `docs/contracts/openapi.yaml`.

See `PROJECT_STATUS_AND_PLAN.md` for the phased plan and delegation split.
