2# Stats App — Status and Implementation Plan

**Owner:** Naman (SSE) · **Date:** 18 August 2026, last updated 29 August 2026 · **Status:** working document

Consolidates `Stats_App_Answers_v1_0.docx`, the Aug 15 v1.2 amendment pack,
`Stats_App_Internal_Decisions_v1.0.docx`, and the ARD (v1.1) into one picture:
what is settled, what is still open, who owns each open item, and what gets built
in what order.

---

## 0. Document hygiene — DONE (16 August 2026)

Four documents contradicted the answered decisions. Anyone reading them — human or
AI — would have built the wrong thing. All four are now updated to v1.2.

| File | What was wrong | Status |
|---|---|---|
| `CLAUDE.md` (root) | MongoDB primary; C1, Q17, Q21, Q39, D15 listed as unresolved — all answered | **Rewritten.** PostgreSQL, per-type queues, `ts.device` retention, open questions reduced to what is genuinely open and split by owner. |
| `IMPLEMENTATION_RULES.md` (was `CLAUDE.md — Stats App Implementation Rules.md`) | §1 called MongoDB an infrastructure dependency; §3 said "collection"; §5 blocked on Mongo retention and on decisions since answered | **Updated and renamed.** Mongo residue removed; §5 split into *blocked*, *already decided*, and *ours to decide*. |
| `DEVELOPMENT.md` | Mongo Compose stack, Mongo URIs, TTL-index guidance, and **retention keyed on `ts.server`** — the opposite of the answer | **Rewritten** for PostgreSQL, Flyway, Testcontainers, per-type queue naming, `ts.device` retention, answered behavioural defaults. |
| `SKILLS.md` | TTL-index retention procedure, "collection", PyMongo, C1/Q10/Q23 treated as open | **Updated.** Skill 1 and Skill 5 rewritten for PostgreSQL; day-one trap briefing added to Skill 7. |

Authority order, in case of any remaining disagreement: this document and the
v1.2 amendment set win; the v1.0 `.docx` files are historical provenance only.

---

## 0b. ARD reconciliation — DONE (18 August 2026)

`docs/source/Datahub_Core_StatsApp_ARD_v1_1.pdf` is the current ARD and supersedes
`ARD_v1_0.pdf`, which is kept for provenance only. It is the most detailed design
document in the set. It is dated after the v1.2 amendments but is **derived from
the v1.0 sources**, so it is a parallel proposal to reconcile — not an authority
that overrides them.
It independently agrees on PostgreSQL-only, per-type queues, TopicExchange, three
images, Java 21, `ts.device` retention, and 200-after-confirm.

Six conflicts were ratified and propagated through every document on
18 Aug 2026. Full register in `CODE_START_CHECKLIST.md` §A2.

| Gate | Decision |
|---|---|
| G1 | Main queue stays `app_statsstore.<type>`; retry and DLQ take the ARD suffixes `app_statsstore.<type>.retry.<tier>` and `app_statsstore.<type>.dlq` |
| G2 | Composite key is `(ts_device, uid)` — ARD column order, matching the partition key and the mandatory sort |
| G3 | Micro-batching is the target; build individual inserts first behind a batch-shaped interface. Amends D11 |
| G4 | Three retry tiers, delays and attempt cap externalised, starting 30 s / 5 min / 30 min |
| G5 | `ADR-nnn` means this repo's ADRs; the ARD's are cited `ARD-ADR-nn` |
| G6 | Spring Boot pinned at 3.5.16 in `gradle/libs.versions.toml`. The "provisional pending Q1a" caveat was removed on 18 Aug 2026 when Q1a closed |

**Adopted from the ARD — status, 24 Aug 2026.** These were recorded as "adopted
without conflict" on 18 Aug but were never propagated into an ADR, contract or
migration, so they were living only in this paragraph. Their real status:

| ARD item | Status |
|---|---|
| Confirming the retry/DLQ handoff **before** ACKing the original | **Adopted and propagated** — ADR-005, "The ACK ordering hazard" |
| Signed cursors binding tenant and filters | **Adopted and propagated** — `docs/contracts/openapi.yaml`, `Cursor` parameter |
| The envelope column list including `ingested_at` and `schema_version` | **Resolved — ADR-003 D7, 24 Aug 2026.** `ingested_at` is **rejected as a rename** of `received_at`; `schema_version smallint NOT NULL` is **adopted**. Both are in `V1__lorastats_table.sql` as built |
| Quorum queues | **Adopted and propagated** — ADR-002 §8 declares every main, retry and dead-letter queue with `x-queue-type: quorum`, from the shared constants only. `AmqpTopologyTest` asserts the argument map |
| Writer deployed once per stat type via `STAT_TYPE`, plus a sixth scheduled invocation for partition maintenance | **Adopted with one deliberate difference** — ADR-003 D8 takes the one-deployment-per-type shape, built in T-019 as `LorastatsDeploymentCondition`. The sixth scheduled invocation was **not** taken: partition maintenance is a scheduled task inside every writer replica behind the D3 advisory lock (ADR-003 D1), which N replicas per type require anyway. Compose still runs a single image — that is a dev convenience, not the production shape |
| `mandatory` routing, unroutable treated as a failed acceptance | **Adopted and propagated** — ADR-002 §9. Input publishes with `mandatory=true` and a `ReturnsCallback`; a returned message is a failed acceptance: 503 plus `records_unroutable{type}`. T-015 is the implementation |
| Separate database roles per module | **Adopted and propagated** — ADR-006: `stats` (write) for the Writer, `stats_ro` (SELECT only) for the API, and **no database role at all** for Input, which holds no datasource. T-009 is PARTIAL on the *proof*, not the decision — nothing yet tests that a write as `stats_ro` is refused |
| RLS with `SET LOCAL app.tenant_id` | **Not adopted, and it conflicts.** ADR-006 enforces isolation as a mandatory query predicate, not row-level security. RLS would be defence in depth, not a replacement — it needs its own ADR before anyone builds it. **The one genuinely unresolved row here** |

> **Reconciled 3 September 2026.** Five of these six rows were carrying "not
> adopted / open" long after the decision had actually been taken — four of them on
> 24 August, in ADR-002 §8/§9, ADR-003 D7/D8 and ADR-006, and two of those are
> built. Only RLS is still genuinely undecided, and it is undecided in the narrow
> sense that adding it as defence in depth would need its own ADR; it is already
> **rejected as a replacement** for the query predicate.
>
> This is the failure the paragraph above warned about, running in the other
> direction: "we adopted it" and "it exists nowhere" are indistinguishable to the
> next reader, and so are "still open" and "settled a fortnight ago". A stale
> *open* item is the more expensive of the two, because someone eventually spends a
> day re-deciding it.

**Still open after the ARD, as it stood on 18 Aug:** it did not close Q1a
(reference project), Q4a (`netstats`), Q8a/Q8b (auth values), Q13a (burst
profile), or D14 (unknown fields — the ARD does not address it at all). **All of
those except D14 were then closed the same day by `2B-ANSWERS-18AUG2026.md`.** It
proposes but cannot ratify D5a (99.9% / RPO~0 / RTO 30 min) and D5b
(`expired_on_arrival` → DLQ). **On 29 Aug 2026
engineering agreed D5b** (adding a non-zero-counter alert) **and put a counter-proposal
to product on D5a: RPO ≤ 5 min, RTO 1 h per partition / 24 h full cluster.** The ARD's
`RPO~0 / RTO 30 min` is not achievable at multi-terabyte scale and was never costed.
**Both were SIGNED on 4 September 2026** — D5a as the counter-proposal (DL-111), D5b as
agreed (DL-112). T-051 and T-026 are unblocked.

~~**Chase:** the ARD references `Stats_App_OpenAPI_v1_draft.yaml` as "delivered
separately".~~ **Closed 21 Aug 2026.** It was never delivered, and it was always
ours to produce — ZC's answer to C2 was *"please create an OpenAPI definition and
we will confirm/decline it."* `docs/contracts/openapi.yaml` is that artifact:
OpenAPI 3.1, GET-only, Unix-seconds filters, keyset pagination. It is a draft
pending ZC confirmation and it gates Phase 4.

---

## 1. Settled — build against these

### Platform and delivery

| Decision | Value |
|---|---|
| Language / JDK | Java 21 |
| Framework | **Spring Boot 3.5.16** — pinned in `gradle/libs.versions.toml` (G6). Final, not provisional: Q1a closed 18 Aug 2026 with no reference repo to reconcile against |
| Build | Gradle multi-module: `stats-common`, `stats-input`, `stats-writer`, `stats-api` |
| Deployables | One Docker image per module. Three images. |
| Database | **PostgreSQL only.** MongoDB is out of scope — no drivers, no repositories, no Compose service. |
| Delivery model | Phased delivery accepted, **per stat type** |

### Data and scope

| Decision | Value |
|---|---|
| V1 stat types | `lorastats`, `ltestats`, `sysstats`, `omsstats`, `netstats` — all five |
| `omsstats` | In scope. Its absence from the STA-21/STA-33 lists was a typo. |
| `omsstats` timestamp | Heimdal already sends `ts.server`. **No normalisation on our side.** `ts.converter` handling is dead. |
| `netstats` schema | **Strict and final** (Q4a, 18 Aug 2026): `eth0`/`wwan` mandatory, other interfaces allowed, `apipa` optional, counters digit-only strings stored as `BIGINT`. The Writer must range-check against the signed `BIGINT` maximum. IPv6 (Q4b) closed provisionally on 4 Sep 2026 — **no, for now** (DL-110) — with a metered per-field rejection code whose non-zero count reopens it. |
| Every record has a tenant | Yes. A missing tenant is a validation failure, not a placeholder write. |
| Isolation field | `tenant`, never `maintenant` |
| `uid` | Unique; Heimdal does not deliver duplicates. Store as its own PG column; uniqueness is `PRIMARY KEY (ts_device, uid)` per partition (ADR-003, G2), and suppressed duplicates are metered. |
| Retention basis | **`ts.device`**, 90 days default, externally configurable |
| Published message | Byte-identical to what was received. Input transforms nothing. |

### Messaging

| Decision | Value |
|---|---|
| Exchange | `app_stats_input`, **TopicExchange** |
| Routing key | The exact stat type literal |
| Queues | **One durable queue per type** — `app_statsstore.<type>`. C1 is closed; the singular name was a prefix. |
| Retry / DLQ | Per type: **three** retry tiers `app_statsstore.<type>.retry.<tier>` plus `app_statsstore.<type>.dlq`, DLX `app_stats_dlx`. Tier delays and the attempt cap are externalised; starting values 30 s / 5 min / 30 min. *(G1/G4, 18 Aug 2026)* |
| HTTP success timing | Only **after** publisher confirm |
| Broker unavailable | Non-2xx (503). No local buffering — Input stays stateless. |
| Upstream recovery | Heimdal Core owns dead-lettering and retry on any non-2xx. Nothing to build on our side. |

### API and errors

| Decision | Value |
|---|---|
| Endpoint definitions | None exist. **We draft the OpenAPI; ZC confirms or declines it.** This is an action on us, not a wait. |
| Error shape | RFC 9457 Problem Details — confirmed |
| Dominant query pattern | `(tenant, clsbox, ts.device)` |
| Roles | **`stats.readOnly`** (read) and `stats.ingest` (ingest), realm roles in `statsapp`. Engineering proposal, live in the dev realm; ZC confirmation (Q8b-2) is a config change. Not the bare `readOnly` placeholder. |

### Capacity

160,000 gateways × 6 reports/hour × 24h:

| Scope | Records/day | Records/sec (average) |
|---|---:|---:|
| Per type | 23,040,000 | 267 |
| Four types | 92,160,000 | 1,067 |
| Five types | 115,200,000 | 1,333 |

Note the answer document's formula (`160k*10*6*24`) double-counts the interval;
ten minutes means six reports per hour, not ten. Corrected figures above.

---

## 2. Open — and who owns each

The old docs treat all of these as equal blockers. They are not. Split by owner:

### 2a. Ours to decide — decide these, do not escalate

Hasan explicitly returned these to engineering. Each needs an ADR, not an email.

| Item | Recommendation | Blocks |
|---|---|---|
| Consumer ACK timing (D7) | Manual ACK **after** the successful PG transaction | Writer |
| Individual vs batched writes (D11) | **Amended (G3).** Bounded micro-batching (100–500 records or 50–200 ms) with single-record fallback is the target; the fallback preserves per-record DLQ precision. Build individual inserts first behind a batch-shaped interface so batching is a benchmarked tuning change, not a rewrite | Writer |
| Invalid filter combinations (D12) | 400 `invalid_time_range` on inverted ranges; document the max query window | API |
| Health checks (D13) | Separate liveness/readiness; readiness probes only the dependencies that module actually needs | All three |
| Log format (D1) | Structured JSON to stdout, `uid` as correlation ID, `module`/`event`/`statType`/`tenant`/`clsbox` fields | All three |
| Unknown payload fields (**D14**, was mislabelled `D6`) | Accept and preserve unknown `data` fields; reject unknown/invalid envelope fields. Never let a Jackson default decide this silently. | Input |
| Gradle DSL, package layout, CI shape | **Ours — Q1a closed, there is nothing to match.** Kotlin DSL, version catalog, `com.zenner.datahub.stats.<module>`, `./gradlew build` plus Testcontainers on every push, build fails on a failed fixture assertion (ADR-001) | Foundation |

**Now closed in ADR-003** (persistence, retention, indexes, APPROVED 24 Aug 2026):
PostgreSQL 17; daily `RANGE (ts_device)` partitions with detach-and-drop retention
instead of batched deletion; typed columns for the four stable types and a `jsonb`
hybrid for `netstats`; `stats-writer` owns retention behind a `pg_advisory_lock`;
Flyway.

Three consequences fell out of that work, and none of them are engineering's to
close alone:

| Item | Needs |
|---|---|
| `uid` uniqueness becomes `(ts_device, uid)` — in that column order — scoped per partition — a global unique index is not possible on a partitioned table | A formal amendment to **STA-21B**. Redelivery still cannot duplicate, because a redelivered record carries the same `ts_device`. |
| ~~Late-arriving records have no live partition to land in~~ | **CLOSED 4 Sep 2026 — D5b SIGNED (DL-112).** Skip, meter `records_expired_on_arrival`, ACK, alert when non-zero. T-026 unblocked. Q15b (DL-106) confirms `ts.device` is genuine device time, so the path is real |
| ~~Backup / PITR / restore targets~~ | **CLOSED 4 Sep 2026 — D5a SIGNED (DL-111):** RPO ≤ 5 min via continuous WAL archiving; RTO 1 h single partition, 24 h full cluster. Obliges archiver-lag alerting (a stalled archiver silently converts RPO from 5 min to *"however long ago it stopped"*) and a **timed** T-051. |

### 2b. Was "needs ZC" — ANSWERED 18 August 2026

All seven items were closed on 18 Aug 2026. Three were answered outright, two were
delegated back to engineering ("you suggest"), one was a build instruction ("make
it"), and one turned out not to exist. The reasoning and every derived engineering
decision are in `docs/decisions/2B-ANSWERS-18AUG2026.md`. **Do not reopen these.**

| Item | Outcome |
|---|---|
| Internal reference repo | **It does not exist.** Nothing was ever going to arrive. Spring Boot 3.5.16 is pinned rather than provisional, and CI, logging, secrets and deployment conventions are ours to define (ADR-001). |
| `netstats` nested rules | **Answered.** `eth0`/`wwan` mandatory; other interfaces allowed via `patternProperties`; `apipa` optional (ZC's own sample omits it on `wwan`); counters stay digit-only strings on the wire, `BIGINT` in the column. The schema is strict. **The Writer must range-check counters** — 19 digits is wider than signed `BIGINT`. |
| Inbound Heimdal auth | **Deferred by decision.** Build Input without it, behind `stats.input.auth.mode=none\|mtls\|oauth2` with no default in code, a `WARN` on every `none` startup, and a test that refuses `prod`. **Risk accepted: anything reaching the Input port can write until Q8a-2 closes.** |
| Keycloak realm and claims | **Proposed by engineering and live in the dev realm.** Realm `statsapp`; clients `stats-api` / `stats-input` / `stats-dev-cli`; audience `stats-api`; read role `stats.readOnly`; ingest role `stats.ingest`; tenant claim `tenant`, **single-valued String** — because a multi-valued claim turns isolation into `tenant IN (...)` and one misconfigured user then reads several tenants with every test still green. |
| Burst profile | **Assume the worst until measured.** Synchronised 10-minute boundaries: 800,000 records per cycle, design target **13,333/s** (10× average). Input sized for peak, Writer for drain (~2,000/s per type), prefetch 500, `max_wal_size` for the burst. **Alert on oldest message age, never on queue depth** — a depth spike each cycle is the buffer working. |
| Replay burst | **Ask Heimdal to cap replay at 2× nominal (2,667/s).** A 6-hour outage is 28.8M messages and ~3 hours of replay at that cap, holding total ingest at 4,000/s. A 503 during replay is the backpressure channel working, not an incident. |
| Realistic samples | **Built, then rebuilt on production 29 Aug 2026 (ADR-007).** 76 fixture files across `valid/`, `invalid/`, `edge/`, `malformed/`, including 9 byte-faithful production records; `validate-fixtures.py` asserts 88 and exits non-zero for CI (the 76th is `lorastats-clsbox-lowercase.json`, added 5 Sep 2026 by DL-115). The production sample invalidated every envelope field name and four of the five payload schemas. |

### 2b-2. What is genuinely still open with ZC

| Item | Why it matters | Blocks |
|---|---|---|
| **Q8a-2** — Heimdal inbound auth: mTLS or OAuth2, issuer, audience, identity, rotation | The Input port is unauthenticated until it closes | Nothing today. **Must close before any shared deployment.** |
| **Q8b-2** — confirmation of the proposed Keycloak values | Every value is a property with no default, so this is a config change | Hardening sign-off, not development |

**Q13b, Q13c, Q4b and the four Q15 questions were removed from this table on 4 September 2026** — all answered. **Q14a and Q5a were removed on 29 August 2026** — both decided, not answered. See §2c and §2d. Only the two auth items above remain owed by ZC.

### 2c. Decided 29 August 2026 — was "conflicted" or misfiled

Six items, four of which were never anyone else's to answer. **Approver:** Naman
Vashishtha. Full reasoning, and the framing to take into the two conversations that
remain: `claude/OPEN_QUESTIONS_STATUS_29AUG2026.md`.

| Ref | Decision | Lands in |
|---|---|---|
| **Q3a** | **Only broker-connected modules declare topology** — Input and Writer, from `stats-common` constants. `stats-api` gets no RabbitMQ connection. Read literally, Hasan's "every module have to declare the exchanges, queues and bindings" hands the read-only API a broker dependency it has no use for and makes a third declaring module a `RESOURCE_LOCKED` risk. The platform ack is a courtesy; **T-032 is the enforcement**, and it proves at build time what prose only asserts | ADR-002 §11 |
| **Q14a** | **Clock skew: reject at Input** beyond `stats.input.validation.max-clock-skew` (default `PT5M`). 400 with the field code `clock_skew_exceeded`, metered as `records_clock_skew_rejected{type}`. Partition pre-creation is finite, so a 2038 clock either demands unbounded partitions or fails deep in the Writer where the caller cannot be told. The check is cross-field and unexpressible in JSON Schema, so the five clock-skew fixtures stay schema-valid with an explicit `$expect: "input-reject"` marker | ADR-003 D9; input contract §2/§4; T-013 |
| **Q5a** | **115.2M records/day.** 160k gateways × 6/h × 24h × 5 types is arithmetic; "around 10 million" reads as an early single-type estimate and is withdrawn. It never blocked freezing the schema — daily `RANGE` partitions, `BIGINT`, `(ts_device, uid)` and the composite index are identical at both figures. The delta is hardware, not rework | ADR-003; §1 capacity table |
| **DLQ retention** | **No TTL, ever.** A TTL'd dead-letter queue is silent data loss, and a DLQ message has already survived three retry tiers. Alert on depth > 0 and oldest-message age; manual disposition within 14 days; redrive by an authorised ops role, per type, capped at 2× nominal, root cause first | ADR-002 §12; `amqp-topology-v1.md` §6 |
| **`pg_partman`** | **Never a dependency.** Pre-creation is a scheduled task in `stats-writer` behind the D3 advisory lock — needed anyway, because D8 puts N replicas per type and N replicas racing to create tomorrow's partition is what the lock exists to prevent | ADR-003 D1 |
| **D5b** | **Engineering agrees** the late-arrival policy — skip, meter `records_expired_on_arrival`, ACK — and adds an **alert when the counter is non-zero**, because a systemic gateway backlog and a one-off look identical in the raw count. **SIGNED 4 Sep 2026 (DL-112) and T-026 is unblocked** — held until then because Q15b left it possible that `ts.device` was stamped on arrival, in which case the policy governed a path production never takes | ADR-003 |

---

## 3. Implementation plan

Sequencing principle: **one stat type end to end before any parallel work starts.**
The first vertical slice is the template every other type is copied from. Fan out
before it exists and you get five divergent implementations to reconcile.

### Phase 0 — Unblock (this week, you)

Not code. This is the highest-leverage week in the project.

1. ~~Doc hygiene per §0~~ — **done 16 Aug.**
2. ~~Write ADR-001 with an exact Spring Boot version~~ — **done 18 Aug (G6):**
   3.5.16 pinned in `gradle/libs.versions.toml`, labelled provisional. Still chase
   ZC for the internal reference project.
3. Send **one** consolidated message to ZC covering everything in §2b.
4. Draft `openapi.yaml` from `contracts/openapi-v1-design.md` and circulate it.
   C2's answer put the ball in our court — this is the long pole on Module 3.
5. Convert §2a into ADRs 003/005/006 and mark them accepted.

**Exit:** platform baseline pinned, OpenAPI circulated, ZC questions sent.

### Phase 1 — Foundation (you + 1 junior)

Gradle multi-project; `stats-common` with the envelope model, stat-type enum, AMQP
topology constants and Problem Details; Docker Compose with PostgreSQL, RabbitMQ,
Keycloak; Flyway; Testcontainers; CI; structured logging.

**Exit:** `./gradlew build` green, `docker compose up` gives a working local stack,
one Testcontainers test hits a real PostgreSQL.

### Phase 2 — Reference vertical slice: `lorastats` (you)

The whole path for exactly one type. Input validates against the JSON Schema and
publishes with publisher confirms; per-type durable topology with retry and DLQ;
Writer consumes, writes to PostgreSQL, manual-ACKs after commit; `PRIMARY KEY
(ts_device, uid)` with metered `ON CONFLICT DO NOTHING`; the
`(tenant, clsbox, ts_device DESC, uid DESC)` index; retention job keyed on
`ts.device`; per-type metrics.

Decide typed columns vs `jsonb` here — everything else inherits it.

**Exit:** valid record lands in the table; invalid returns 400 and publishes nothing;
broker down returns 503; DB down grows the queue and eventually DLQs; duplicate `uid`
is a harmless no-op; retention boundary test passes on both sides.

### Phase 3 — Type fan-out (juniors, parallel)

`ltestats`, `sysstats`, `omsstats` — one per junior, each an additive copy of the
Phase 2 pattern. `netstats` last — its schema was finalised by Q4a on 18 Aug 2026, so
nothing gates it but the reference slice. If any of these requires
editing Phase 2 code, the abstraction is wrong — that comes back to you.

**Exit:** all approved types ingesting; poison-message isolation test proves one bad
type does not stall another.

### Phase 4 — Stats API (you + 1 junior)

Only after the OpenAPI is confirmed. Keycloak resource server, tenant claim mapping,
GET-only routes, tenant predicate in every query, keyset pagination with a unique
tie-breaker, error mapping.

**Exit:** 401/403/405 tests pass; a `tenant != maintenant` fixture proves only
`tenant` controls isolation; pagination is stable under concurrent writes.

### Phase 5 — Operational readiness

Load test at 1,333/s sustained plus the burst profile; DB and broker outage drills;
graceful shutdown and redelivery; DLQ redrive rehearsal; backup/restore; dashboards,
alerts, runbook.

---

## 4. Delegation — what juniors get, what you keep

The line: **delegate work whose failure is visible in a test; keep work whose failure
is invisible until production.** Tenant isolation, ack semantics, and retention basis
all pass naive tests while being wrong.

### Delegate

| Work | Phase | Notes |
|---|---|---|
| JSON Schemas per type + full fixture sets | 0–1 | Mechanical from the SRS field lists. **You review the `required` arrays** — a wrongly-optional field silently admits bad data. |
| Docker Compose dev stack, Keycloak dev realm | 1 | Self-contained, verifiable, good onboarding task |
| Type fan-out for types 2–4 | 3 | Only after Phase 2 exists. Pattern-following by design. |
| Micrometer metrics, actuator health indicators, dashboards | 3–5 | Clear spec in STA-27A |
| OpenAPI examples, error catalogue, API docs | 4 | Against your frozen contract |
| Load-test harness (k6/Gatling) | 5 | Building the harness is delegable; interpreting results is not |
| Runbook drafts, DEVELOPMENT.md rewrite | 1–5 | Genuinely useful, and it teaches them the system |
| Fixture library including `tenant != maintenant` records | 1 | They build fixtures; **you write the isolation assertions** |

### Keep

| Work | Why |
|---|---|
| ADRs 001, 003, 005, 006 | Architecture decisions with no rollback |
| Tenant isolation — implementation *and* its tests | Security control. A junior writing both the code and its test can be consistently wrong and green. |
| Publisher confirm + ACK-after-commit semantics | Subtly wrong versions look identical until you lose data |
| PostgreSQL schema, partitioning, index design, migrations | Expensive to change at 115M rows/day |
| Retention job | Wrong timestamp or unbounded delete = data loss or a locked table |
| Keyset pagination and cursor encoding | Off-by-one duplicates or skips records; hard to spot |
| Security config: Keycloak, claim mapping, inbound auth | — |
| Phase 2 reference slice, end to end | Everything else is a copy of it |

### Brief every junior on these four traps

1. **`tenant` vs `maintenant`.** They are equal in most sample data. Filtering on the
   wrong one passes every test and leaks data in production. Every fixture you write
   must set them to different values.
2. **Unix *seconds*, not milliseconds.** Treating `ts.device` as millis gives you 1970.
3. **Unsorted pagination is not stable.** Always sort on an indexed field with a
   unique tie-breaker — not only when the caller asks for a sort.
4. **Never swallow an exception.** Every failure path returns an error, dead-letters,
   or logs at WARN/ERROR with `uid` and stat type. Silence is the one unacceptable
   outcome.

### Definition of done for a delegated type

Valid records stored · invalid returns 400 with Problem Details and publishes nothing ·
forced write failure reaches the type DLQ · readable via GET, tenant-scoped · non-GET
returns 405 · retention configured and boundary-tested · pagination stable · OpenAPI
updated · per-type metrics exposed · **no existing type's code was modified** (STA-22).

---

## 5. Your critical path

```
Pin Spring Boot version ──┐
Send ZC questions ────────┼──> Foundation ──> lorastats slice ──> fan-out ──> ops
Draft + circulate OpenAPI ┘                                  └──> API ──────┘
```

Three things gate everything and all three are movable this week: the Spring Boot
version (48h, then proceed provisionally), the ZC question set (one message), and
the OpenAPI draft (yours to write, not theirs to supply).
