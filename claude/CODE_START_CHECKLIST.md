# Stats App — Code-Start Checklist

This is the required gate between good architecture documents and safe implementation. Each completed decision must have an approver, decision date, and link to the resulting ADR or approved specification.

## A. Stop/go decision register

| ID | Decision | Required decision | Owner | Status | Blocks |
|---|---|---|---|---|---|
| D-01 | Platform baseline | Java/Kotlin, JDK, Spring Boot, Gradle DSL, reference project, approved dependency baseline. | Platform engineering | **DECIDED — ADR-001.** Java 21, Spring Boot 3.5.16 pinned (G6), Gradle Wrapper + Kotlin DSL + version catalog. Q1a closed 18 Aug 2026: **there is no reference project**, so CI, logging, secrets and deployment conventions are defined by ADR-001 rather than inherited. | Nothing. |
| D-02 | V1 stat types | Confirm all five types, including formal rules for `netstats.json` nested interfaces and optionality. | Product + Heimdal owner | **DECIDED (Q4a, 18 Aug 2026).** All five types. `eth0`/`wwan` mandatory, other interfaces allowed, `apipa` optional, counters digit-only strings → `BIGINT`. Schema is strict. Open: IPv6 (Q4b), which widens one `$def`. | Nothing. |
| D-03 | Inbound contract | Path, content type, request size, timeouts, auth, retryability, replay/backfill and duplicate behavior. | Heimdal owner + security | **PARTIAL.** Response matrix, publisher-confirm timing, 503-on-broker-failure and duplicate behaviour are decided (ADR-005). Still open: exact success code (200 vs 202), path confirmation, request size/timeout limits, and the agreed replay cap (Q13c). | Request-limit properties only. Build the endpoint. |
| D-04 | Inbound authentication | mTLS or OAuth2 client-credentials/JWT; client identity; issuer/audience; rotation; 401/403 rules. | Security + platform | **DEFERRED BY DECISION (Q8a, 18 Aug 2026).** Build Input without Heimdal auth; auth is a config-selected filter `stats.input.auth.mode=none\|mtls\|oauth2`, **no default in code**, `WARN` on every `none` startup, and a test that refuses to start under `prod`. **Risk accepted: until Q8a-2 closes, anything reaching the Input port can write.** Must close before any shared deployment. | Nothing today. Blocks shared deployment. |
| D-05 | Tenant policy | Required record field, validated token claim, `tenant` versus `maintenant`, tenantless records, privileged access model. | Security + product | **DECIDED — ADR-006 ACCEPTED.** Isolation on `tenant`, from the validated claim only, in every query/count/cursor. Missing or empty claim → 403, never "all tenants". Cross-tenant lookup → 404 indistinguishable from not-found. Privileged cross-tenant access out of scope. Open: ZC confirmation of the Keycloak names (Q8b-2), a config change. | Nothing. |
| D-06 | AMQP topology | Exchange type/name, queue naming, bindings, durable settings, retries/backoff, DLQ/redrive, topology owner. | Platform + engineering | **DECIDED — ADR-002 APPROVED.** `app_stats_input` TopicExchange, routing key = stat type, `app_statsstore.<type>` main queue, **three** retry tiers `.retry.<tier>` (30 s / 5 min / 30 min, externalised) and `.dlq` per type, DLX `app_stats_dlx`, all durable (G1/G4). Open: Q3a — confirm with platform that `stats-api` gets **no** broker connection. | Nothing. |
| D-07 | Delivery/idempotency | At-least-once semantics, publisher confirms, ACK point, `uid` uniqueness scope, duplicate outcome. | Heimdal owner + engineering | **DECIDED — ADR-005 ACCEPTED.** At-least-once; 2xx only after publisher confirm; manual ACK after commit; retry/DLQ handoff publish-confirmed **before** ACKing the original; `ON CONFLICT (ts_device, uid) DO NOTHING`, metered. Open: Q13c, the 2× replay cap, which we cannot enforce. | Nothing. |
| D-08 | PostgreSQL persistence | Table/column design, typed columns versus `jsonb`, `uid` uniqueness scope, partitioning, `(tenant, gateway, ts.device)` indexes, retention job/partition strategy, migrations, backups. | Data/platform + engineering | **DECIDED — ADR-003 APPROVED 24 Aug 2026.** PG17; daily `RANGE (ts_device)` partitions; typed columns for four types, `jsonb` hybrid for `netstats`; `PRIMARY KEY (ts_device, uid)` per partition; Flyway. Two rows remain open *inside* the approved ADR: RPO/RTO (D5a) and the late-arrival policy (D5b). | Writer. |
| D-09 | API contract | OpenAPI paths, roles/claims, filters, time format, response envelope, sorting, cursor/pagination, limits, error catalog. | API consumers + security | **DRAFTED 21 Aug 2026 — `docs/contracts/openapi.yaml`.** GET-only `/v1/stats/{type}`, Unix-seconds filters, required `from`/`to`, `limit` 1–1000 default 100, keyset cursor, fixed `ts.device DESC, uid DESC` sort, RFC 9457 errors. Awaiting ZC confirm/decline — which ZC asked us to initiate. Provisional inside it: the maximum query window. | Freezing the contract. Controllers wait on confirmation. |
| D-10 | Deployment/scaling | Three application artifacts, runtime platform, per-type writer scaling, environment configuration, readiness probes. | Platform engineering | PENDING APPROVAL | Packaging and release. |
| D-11 | Capacity and SLOs | Base design on 160,000 gateways reporting every 10 minutes: 23.04m records/type/day, 92.16m/day for four types, 115.2m/day for five types; also define burst rate, payload sizes, reads, latency, backlog, RTO/RPO, and replay target. | Product + operations | **PARTIAL (Q13a, 18 Aug 2026).** Engineering assumes synchronised 10-minute boundaries: design target **13,333/s** (10× average). Input sized for peak, Writer for drain (~2,000/s per type), prefetch 500, `max_wal_size` for the burst. Still open: **Q13b gateway jitter** (worth 10× on hardware), **Q13c** replay cap, **RPO/RTO** (D5a), and the Q5a volume contradiction. | RPO/RTO gates production readiness. Sizing can proceed. |
| D-12 | Data handling | Data classification, masking, log/DLQ retention, access controls, encryption, audit, backup/restore, deletion. | Security + compliance | PENDING APPROVAL | Production logging, DLQ, operations. |

> **Rule:** A developer may implement only the work that does not rely on an unanswered row. A row marked `PENDING APPROVAL` is not permission to assume the recommended option.
>
> **Register reconciled 24 August 2026.** Most rows are now decided; the status
> column names the governing ADR or gate. Two rows carry a **risk accepted**
> rather than an answer — D-04 (Input is unauthenticated until Q8a-2 closes) and
> D-11 (RPO/RTO still unset) — and those are the ones to re-read before any shared
> deployment. The remaining `PENDING` rows are genuinely open: D-10, D-12, and the
> product/operations halves of D-03 and D-11.

### A2. Gate decisions ratified 18 August 2026

These six conflicts between `CLAUDE.md` v1.2 and `Datahub_Core_StatsApp_ARD_v1_0.pdf` were closed in a single sitting because each one gets baked into a file that is expensive to change later. **Approver:** Naman Vashishtha. **Status:** DECIDED — implement as written.

| ID | Conflict | Decision | Lands in |
|---|---|---|---|
| G1 | Queue naming — ARD `app_statsstore.<type>.q` / `.dlq` versus ours `app_statsstore.<type>` / `app_statsdlq.<type>` | Main queue stays `app_statsstore.<type>` verbatim (a MUST traced to STA-10A). Retry and DLQ adopt the ARD's suffixes: `app_statsstore.<type>.retry.<tier>` and `app_statsstore.<type>.dlq`. One consistent prefix. | `stats-common` topology constants; `CLAUDE.md` §1 + rule 5 (done) |
| G2 | Composite key column order — ARD `(ts_device, uid)` versus rule 13's `(uid, ts_device)` | **ARD's `(ts_device, uid)`.** `ts_device` leads so the key matches the partition key and the dominant `(ts_device DESC, uid DESC)` sort. | First Flyway migration; `CLAUDE.md` rule 13 (done) |
| G3 | Individual writes versus micro-batching — D11 versus ARD AD-13 | Micro-batching (100–500 records or 50–200 ms) with single-record fallback is the **target**; single-record fallback preserves the per-record DLQ precision D11 was protecting. Build individual inserts **first** behind a batch-shaped interface, so batching is a benchmarked tuning change rather than a rewrite. | `stats-writer`; `CLAUDE.md` §7 D11 amended (done) |
| G4 | Retry tier count — one retry queue versus three tiers | **Three tiers.** All delays and the maximum attempt count externalised to `application.properties`, never hard-coded. Starting values 30 s / 5 min / 30 min. Immediate requeue is never used for a persistent failure. | `stats-common` topology constants; `CLAUDE.md` rule 5 (done) |
| G5 | ADR numbering collision — ARD ADR-001…010 clash with repo ADR-001…006 | Repository ADRs stay authoritative and keep the bare `ADR-nnn` form. The ARD's are cited as **`ARD-ADR-nn`** everywhere. Cover this in the first commit-convention walkthrough. | `CLAUDE.md` §8 (done); commit conventions |
| G6 | Spring Boot version — Q1a still open on "> 3.5.x" | Pin **3.5.16** as a single entry in `gradle/libs.versions.toml`. Module build files never declare their own versions, so a platform standard is one line to change. | `gradle/libs.versions.toml`; `CLAUDE.md` §3 + Q1a (done) |

> **G6 update, 18 Aug 2026.** Q1a was answered the same day: **there is no internal
> reference project.** The pin is therefore final rather than provisional, the
> "provisional pending Q1a" comment has been removed from the version catalog, and
> **D-01 is now DECIDED** — CI, logging, secrets and deployment conventions are
> defined by ADR-001 rather than inherited. Nothing about D-01 remains outstanding.

## B. Approved implementation sequence

> **Phase numbering is shared with `claude/PROJECT_STATUS_AND_PLAN.md` §3 and is
> cited by the ADRs.** Phase 0 design lock · 1 foundation · 2 reference vertical
> slice · 3 type fan-out · 4 API · **5 operational readiness and load test**.
> ADR-003 and `docs/contracts/openapi.yaml` both cite these numbers; do not
> renumber one document without the others.

### Phase 0 — Design lock

**Exit criteria:** D-01 through D-12 have owners and due dates; the architecture, SRS, and BRD amendments are reviewed; the OpenAPI/AMQP/schema documents exist as reviewable drafts.

The team may draft contracts, gather samples, create ADRs, and schedule the decision workshop. It may not declare production security, topology, or public endpoint behavior from guesses.

### Phase 1 — Foundation

**Entry criteria:** D-01 is approved.

Create the Gradle multi-project structure, shared envelope model, baseline configuration, Docker Compose development stack with PostgreSQL/RabbitMQ/Keycloak, CI, code-quality tools, PostgreSQL Testcontainers support, and standard error/logging abstractions. This phase creates no public API and does not claim to finalize credentials, AMQP retries, or data retention.

### Phase 2 — Ingestion and persistence for specified types

**Entry criteria:** D-02 through D-08 and D-10 are approved for the type being built. As of 24 Aug 2026 that is satisfied for `lorastats`.

Build **exactly one type end to end** — `lorastats` — as the reference slice every other type is copied from. Fan out before it exists and you get five divergent implementations to reconcile.

Include validation, **no transformation** of the received record, publisher confirmation before any 2xx, durable per-type broker topology with three retry tiers and a DLQ, manual ACK after commit, `PRIMARY KEY (ts_device, uid)` with metered `ON CONFLICT DO NOTHING`, daily `RANGE (ts_device)` partitions with pre-creation, the `(tenant, gateway, ts_device DESC, uid DESC)` index, retention keyed on `ts.device`, per-type metrics, and Testcontainers integration tests.

**Exit criteria:** a valid record lands in the table; an invalid one returns 400 and publishes nothing; broker down returns 503; database down grows the queue and eventually dead-letters; a duplicate `uid` is a harmless no-op **that increments `records_duplicate_suppressed`**; retention boundary tests pass on both sides.

### Phase 3 — Type fan-out

**Entry criteria:** Phase 2's reference slice exists and is green.

`ltestats`, `sysstats`, `omsstats`, then `netstats` — each an **additive** copy of the Phase 2 pattern. If any of them requires editing Phase 2 code, the abstraction is wrong and that is a design smell to raise, not to work around (STA-14A, STA-22).

**Exit criteria:** all approved types ingesting; the poison-message isolation test proves one bad type does not stall another.

### Phase 4 — API

**Entry criteria:** D-05, D-09, D-10, and D-12 are approved and the OpenAPI contract is signed off.

Implement Keycloak resource-server integration, token claim mapping, strict tenant-scoped repository queries, GET-only endpoints, deterministic pagination, response/error mapping, and contract tests. There is no tenant query parameter in the ordinary API unless a separately approved privileged access model exists.

### Phase 5 — Operational readiness and release

**Entry criteria:** D-11 and D-12 are approved; the operations runbook and dashboards exist.

Perform capacity/load testing, broker/DB failure tests, graceful-shutdown and redelivery tests, security tests, backup/restore verification, alert validation, DLQ redrive exercise, and consumer acceptance testing. Production release is blocked until observability and ownership are proven.

## C. Minimum acceptance evidence

| Capability | Evidence required |
|---|---|
| Input validation | Schema test suite with valid, invalid, unknown-type, wrong-type, and timestamp fixtures. |
| Tenant security | Integration test that proves a token for tenant A cannot obtain tenant B data when `tenant` differs from `maintenant`. |
| Reliable delivery | Test showing success only after broker confirmation, at-least-once redelivery, duplicate handling, retry limit, and DLQ behavior. |
| Retention | Index/deletion test at the expiry boundary, configuration reconciliation test, and verification of selected timestamp semantics. |
| API | Approved OpenAPI diff, automated contract tests, 401/403/405 tests, query/pagination/cursor tests, and non-leaky errors. |
| Operations | Metrics on processed/error/duration/queue depth per type, dashboard screenshot or configuration, alert tests, and runbook rehearsal. |

## D. Decision workshop agenda

The fastest path to code is a 90-minute workshop with the Heimdal owner, platform/security owner, API consumer representative, product owner, and lead engineer. Decide D-01 through D-09 in the first session and assign D-10 through D-12 with dates. Bring real non-production sample records and exact delivery/replay behavior from Heimdal.
