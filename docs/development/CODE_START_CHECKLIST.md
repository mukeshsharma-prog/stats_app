# Stats App — Code-Start Checklist

This is the required gate between good architecture documents and safe implementation. Each completed decision must have an approver, decision date, and link to the resulting ADR or approved specification.

## A. Stop/go decision register

| ID | Decision | Required decision | Owner | Status | Blocks |
|---|---|---|---|---|---|
| D-01 | Platform baseline | Java/Kotlin, JDK, Spring Boot, Gradle DSL, reference project, approved dependency baseline. | Platform engineering | **DECIDED — ADR-001.** Java 21, Spring Boot 3.5.16 pinned (G6), Gradle Wrapper + Kotlin DSL + version catalog. Q1a closed 18 Aug 2026: **there is no reference project**, so CI, logging, secrets and deployment conventions are defined by ADR-001 rather than inherited. | Nothing. |
| D-02 | V1 stat types | Confirm all five types, including formal rules for `netstats.json` nested interfaces and optionality. | Product + Heimdal owner | **DECIDED (Q4a, 18 Aug 2026).** All five types. `eth0`/`wwan` mandatory, other interfaces allowed, `apipa` optional, counters digit-only strings → `BIGINT`. Schema is strict. **IPv6 closed provisionally 4 Sep 2026 (Q4b, DL-110): no, for now.** The schema stays IPv4-only and the IPv4 `$def` failure emits a distinct metered per-field code — a non-zero count on it reopens Q4b. | Nothing. |
| D-03 | Inbound contract | Path, content type, request size, timeouts, auth, retryability, replay/backfill and duplicate behavior. | Heimdal owner + security | **PARTIAL.** Response matrix, publisher-confirm timing, 503-on-broker-failure and duplicate behaviour are decided (ADR-005). Clock skew is now decided too (Q14a, ADR-003 D9): a `ts.device` beyond `stats.input.validation.max-clock-skew` is a 400 `clock_skew_exceeded`. **Q13c closed 4 Sep 2026 (DL-109):** Heimdal agrees the 2× replay cap — and it stays unenforceable by us, so 503 backpressure and T-018's rate limit are unchanged. Still open: exact success code (200 vs 202) and path confirmation — **and since 21 Sep 2026 (DL-151) there are two paths to confirm:** `/internal/stats` (Heimdal's) and `/external/stats` (every other caller), one handler, one filter chain, one rate bucket, `source` label on every Input metric. **Transport confirmed HTTP 17 Sep 2026 (DL-145)**; `heimdal-input-contract-v1.md` §0 is the handover table, and `openapi-input.yaml` its OpenAPI twin. **Request limits are no longer waiting on it (T-018, DL-121):** 256 KB and 4,000 req/s per instance are engineering defaults in `stats.input.limits.*`, because an unbounded ingest endpoint is wrong at every value D-03 can return. Closing D-03 is now a property change. | **Nothing** for the internal door — built, bounded, publisher-confirmed. **The external door must not be exposed anywhere shared** until Q8a-2 supplies a token to bind the record's tenant to (T-068, BLOCKED; DL-151 residual). |
| D-04 | Inbound authentication | mTLS or OAuth2 client-credentials/JWT; client identity; issuer/audience; rotation; 401/403 rules. | Security + platform | **DEFERRED BY DECISION (Q8a, 18 Aug 2026).** Build Input without Heimdal auth; auth is a config-selected filter `stats.input.auth.mode=none\|mtls\|oauth2`, **no default in code**, `WARN` on every `none` startup, and a test that refuses to start under `prod`. **Risk accepted: until Q8a-2 closes, anything reaching the Input port can write.** Must close before any shared deployment. **Seam BUILT 5 Sep 2026 (T-017, DL-120):** the mode has no default, `mtls`/`oauth2` fail at startup rather than serving an endpoint that claims to authenticate, and `ProductionAuthModeGuard` refuses to build under `prod`. Spring Security is deliberately not on the classpath yet — it arrives with the mechanism. | Nothing today. Blocks shared deployment. |
| D-05 | Tenant policy | Required record field, validated token claim, `tenant` versus `maintenant`, tenantless records, privileged access model. | Security + product | **DECIDED — ADR-006 ACCEPTED.** Isolation on `tenant`, from the validated claim only, in every query/count/cursor. Missing or empty claim → 403, never "all tenants". Cross-tenant lookup → 404 indistinguishable from not-found. Privileged cross-tenant access out of scope. Open: ZC confirmation of the Keycloak names (Q8b-2), a config change. | Nothing. |
| D-06 | AMQP topology | Exchange type/name, queue naming, bindings, durable settings, retries/backoff, DLQ/redrive, topology owner. | Platform + engineering | **DECIDED — ADR-002 APPROVED.** `app_stats_input` TopicExchange, routing key = stat type, `app_statsstore.<type>` main queue, **three** retry tiers `.retry.<tier>` (30 s / 5 min / 30 min, externalised) and `.dlq` per type, DLX `app_stats_dlx`, all durable (G1/G4). **Closed 29 Aug 2026:** Q3a — only broker-connected modules declare topology, `stats-api` gets **no** broker connection (ADR-002 §11); and DLQ retention — **no TTL, ever**, with alerting, 14-day manual disposition and per-type authorised redrive (ADR-002 §12). **BUILT 5 Sep 2026:** the three tiers, the per-type DLQ and both `app_stats_dlx` routes are declared and exercised against a real broker (T-024, DL-122), and the declarations can no longer drift between modules (T-032, DL-123). No residual. | Nothing. The alerting on top of it is T-060. |
| D-07 | Delivery/idempotency | At-least-once semantics, publisher confirms, ACK point, `uid` uniqueness scope, duplicate outcome. | Heimdal owner + engineering | **DECIDED — ADR-005 ACCEPTED.** At-least-once; 2xx only after publisher confirm; manual ACK after commit; retry/DLQ handoff publish-confirmed **before** ACKing the original; `ON CONFLICT (ts_device, uid) DO NOTHING`, metered. **Q13c closed 4 Sep 2026 (DL-109):** Heimdal agrees the 2× replay cap. We still cannot enforce it, and the damaging failure mode is not the breach — it is tuning our own backpressure away because a commitment exists. | Nothing. |
| D-08 | PostgreSQL persistence | Table/column design, typed columns versus `jsonb`, `uid` uniqueness scope, partitioning, `(tenant, clsbox, ts.device)` indexes, retention job/partition strategy, migrations, backups. | Data/platform + engineering | **DECIDED — ADR-003 APPROVED 24 Aug 2026.** PG17; daily `RANGE (ts_device)` partitions; typed columns for four types, `jsonb` hybrid for `netstats`; `PRIMARY KEY (ts_device, uid)` per partition; Flyway. **Amended 29 Aug 2026:** partition pre-creation is a scheduled task in `stats-writer` behind the D3 advisory lock, with `pg_partman` never a dependency (D1); clock skew is rejected at Input (D9, closes Q14a). **Both rows that were open *inside* the approved ADR are SIGNED as of 4 Sep 2026:** RPO/RTO (D5a → DL-111) and the late-arrival policy (D5b → DL-112). **Built 10 Sep 2026 (T-025, T-026; DL-132, DL-133):** retention is `DETACH PARTITION … CONCURRENTLY` then `DROP TABLE`, never row deletion, and a late record is skipped, metered and ACKed. T-051 is unblocked. | Writer. |
| D-09 | API contract | OpenAPI paths, roles/claims, filters, time format, response envelope, sorting, cursor/pagination, limits, error catalog. | API consumers + security | **DRAFTED 21 Aug 2026 — `docs/contracts/openapi.yaml`.** GET-only `/v1/stats/{type}`, Unix-seconds filters, required `from`/`to`, `limit` 1–1000 default 100, keyset cursor, fixed `ts.device DESC, uid DESC` sort, RFC 9457 errors. Awaiting ZC confirm/decline — which ZC asked us to initiate. Provisional inside it: the maximum query window (tenant-wide list only). **Amended 21 Aug → 21 Sep 2026 (DL-151):** three operations — the list, the device-scoped `/{type}/{clsbox}/{from}[/{to}]` (open-ended one-argument form, foreign `clsbox` is an empty 200, no window limit; T-067) and `/{type}/{uid}`; the `clsbox` query parameter is gone. If the draft already went to ZC, send the delta. | Freezing the contract. ~~Controllers wait on confirmation — ten tasks, T-038…T-046 and T-067.~~ **The ten tasks were built on 26 Sep 2026 ahead of confirmation, by the approver's decision (DL-162)**; this row now gates only the contract's freeze, and a change ZC asks for is rework in `stats-api`. |
| D-10 | Deployment/scaling | Three application artifacts, runtime platform, per-type writer scaling, environment configuration, readiness probes. | Platform engineering | **PARTIAL — split 29 Aug 2026.** *Decided:* three images, one per application module (Q2); per-type Writer scaling via one image deployed once per `STAT_TYPE` (ADR-003 D8); separate liveness/readiness probing only each module's real dependencies (D13); environment configuration by injected env vars (ADR-001). *Still pending platform:* the **runtime platform and release mechanics** — orchestrator, image registry, promotion path, resource requests/limits. | **Packaging and release only.** The decided half is what Phase 2 and Phase 4 need, which is why their entry criteria are satisfied; the pending half gates nothing before Phase 5. |
| D-11 | Capacity and SLOs | Base design on 160,000 gateways reporting every 10 minutes: 23.04m records/type/day, 92.16m/day for four types, 115.2m/day for five types; also define burst rate, payload sizes, reads, latency, backlog, RTO/RPO, and replay target. | Product + operations | **PARTIAL (Q13a, 18 Aug 2026).** Engineering assumes synchronised 10-minute boundaries: design target **13,333/s** (10× average). Input sized for peak, Writer for drain (~2,000/s per type), prefetch 500, `max_wal_size` for the burst. **Q5a closed 29 Aug 2026 at 115.2M/day** — the internal "around 10 million" is withdrawn, and it never blocked the schema. **All three closed 4 Sep 2026.** **Q13b (DL-104): gateways JITTER** across the cycle, so expected steady state is ~1,333/s and 13,333/s becomes configured headroom — hardware is not sized down until the `ts.server mod 600` histogram over the first 24 h of ingest confirms it. **Q13c (DL-109):** the 2× replay cap is agreed and remains unenforceable by us. **D5a SIGNED (DL-111):** RPO ≤ 5 min, RTO 1 h per partition / 24 h full cluster. | Nothing. Production readiness is unblocked; T-051 must now be *timed* against the RTO, not merely completed. |
| D-12 | Data handling | Data classification, masking, log/DLQ retention, access controls, encryption, audit, backup/restore, deletion. | Security + compliance | **PARTIAL.** *Decided:* **DLQ retention — no TTL, ever**, with alerting, 14-day manual disposition and authorised per-type redrive (ADR-002 §12, 29 Aug 2026); **LTE/netstats payloads are forbidden at every log level; other types permit full payloads only at `DEBUG`, which is off in production** — never at `INFO` or above on any path, success or failure — and no tokens or secrets in logs at any level (D1, ADR-001, DL-080 as amended, DL-102; `docs/development/DEVELOPMENT.md` §7 is authoritative); database roles `stats` / `stats_ro` / none-for-Input (ADR-006). *Decided 12 Oct 2026 (D-12 P3, DL-167, approver):* **`imsi` stored raw and masked on every API read; `lac`/`cid` returned as reported**; the no-LTE/netstats-payload logging rule is **enforced** by `LteNetstatsPayloadsAreNeverLoggedIntegrationTest` in all three modules, with the two framework loggers that leaked pinned. *Still pending security + compliance:* data classification, masking policy for **network (`ip`/`dns`) identifiers** — the field inventory found three classes where this row named one — log retention (the number and the access list), and audit evidence. **Input document: `docs/decisions/DATA-SENSITIVITY-INVENTORY.md`.** Two items are misfiled here and belong to **platform**: the log-retention *mechanism* (a Docker log-driver setting, same owner as D-10) and *encryption at rest* (volume configuration, and the same conversation as D5a). **The deadline is T-034**, not a date: `ltestats` is implemented locally as of 8 October 2026 (DL-164), with merge/shared release still gated by D-12 P3; once it ships, ADR-002 §12's "no TTL, ever" makes an unmasked identifier permanent in the DLQ. | Production logging and operations. **Not Phase 4** — the API's own access control is ADR-006, which is ACCEPTED. |

> **Rule:** A developer may implement only the work that does not rely on an unanswered row. A row marked `PENDING APPROVAL` is not permission to assume the recommended option. A row marked **PARTIAL** names exactly which half is decided; only the pending half blocks.
>
> **Register reconciled 24 August 2026; re-reconciled 29 August 2026.** Most rows are
> now decided; the status column names the governing ADR or gate. Two rows carry a
> **risk accepted** rather than an answer — D-04 (Input is unauthenticated until Q8a-2
> closes) and D-11 (RPO/RTO still unset) — and those are the ones to re-read before any
> shared deployment.
>
> **What changed on 29 August 2026.** D-10 and D-12 were blanket `PENDING APPROVAL`
> while D-10 was simultaneously cited as satisfied for Phase 2 entry below — the file
> contradicted itself, and neither row appeared on the tracker's Open Questions sheet.
> Both are now **PARTIAL**, with the decided half and the pending half named separately,
> so the phase entry criteria and the register agree. The genuinely open items are: the
> platform half of D-10, the security/compliance half of D-12, and the request-limit
> half of D-03.
>
> **What changed on 4 September 2026.** D-11 closed entirely (Q13b, Q13c and D5a), and
> **D5a and D5b inside ADR-003 are SIGNED** — see §A3. D-02's IPv6 residual closed
> provisionally with a metered reopening trigger.

### A2. Gate decisions ratified 18 August 2026

These six conflicts between `docs/development/PROJECT_CONTEXT.md` v1.2 and `Datahub_Core_StatsApp_ARD_v1_0.pdf` were closed in a single sitting because each one gets baked into a file that is expensive to change later. **Approver:** Naman Vashishtha. **Status:** DECIDED — implement as written.

| ID | Conflict | Decision | Lands in |
|---|---|---|---|
| G1 | Queue naming — ARD `app_statsstore.<type>.q` / `.dlq` versus ours `app_statsstore.<type>` / `app_statsdlq.<type>` | Main queue stays `app_statsstore.<type>` verbatim (a MUST traced to STA-10A). Retry and DLQ adopt the ARD's suffixes: `app_statsstore.<type>.retry.<tier>` and `app_statsstore.<type>.dlq`. One consistent prefix. | `stats-common` topology constants; `docs/development/PROJECT_CONTEXT.md` §1 + rule 5 (done) |
| G2 | Composite key column order — ARD `(ts_device, uid)` versus rule 13's `(uid, ts_device)` | **ARD's `(ts_device, uid)`.** `ts_device` leads so the key matches the partition key and the dominant `(ts_device DESC, uid DESC)` sort. | First Flyway migration; `docs/development/PROJECT_CONTEXT.md` rule 13 (done) |
| G3 | Individual writes versus micro-batching — D11 versus ARD AD-13 | Micro-batching (100–500 records or 50–200 ms) with single-record fallback is the **target**; single-record fallback preserves the per-record DLQ precision D11 was protecting. Build individual inserts **first** behind a batch-shaped interface, so batching is a benchmarked tuning change rather than a rewrite. | `stats-writer`; `docs/development/PROJECT_CONTEXT.md` §7 D11 amended (done) |
| G4 | Retry tier count — one retry queue versus three tiers | **Three tiers.** All delays and the maximum attempt count externalised to `application.properties`, never hard-coded. Starting values 30 s / 5 min / 30 min. Immediate requeue is never used for a persistent failure. | `stats-common` topology constants; `docs/development/PROJECT_CONTEXT.md` rule 5 (done) |
| G5 | ADR numbering collision — ARD ADR-001…010 clash with repo ADR-001…007 | Repository ADRs stay authoritative and keep the bare `ADR-nnn` form. The ARD's are cited as **`ARD-ADR-nn`** everywhere. Cover this in the first commit-convention walkthrough. | `docs/development/PROJECT_CONTEXT.md` §8 (done); commit conventions |
| G6 | Spring Boot version — Q1a still open on "> 3.5.x" | Pin **3.5.16** as a single entry in `gradle/libs.versions.toml`. Module build files never declare their own versions, so a platform standard is one line to change. | `gradle/libs.versions.toml`; `docs/development/PROJECT_CONTEXT.md` §3 + Q1a (done) |

> **G6 update, 18 Aug 2026.** Q1a was answered the same day: **there is no internal
> reference project.** The pin is therefore final rather than provisional, the
> "provisional pending Q1a" comment has been removed from the version catalog, and
> **D-01 is now DECIDED** — CI, logging, secrets and deployment conventions are
> defined by ADR-001 rather than inherited. Nothing about D-01 remains outstanding.

### A3. Decisions ratified 29 August 2026

Six items that the register and the tracker were carrying as questions for someone
else. Four were never anyone else's to answer; one is a proposal now in front of
product; one has engineering agreement and still needs a signature.
**Approver:** Naman Vashishtha. **Status:** DECIDED — implement as written.
**Full reasoning:** `docs/decisions/OPEN_QUESTIONS_STATUS_29AUG2026.md`.

| ID | Item | Decision | Lands in |
|---|---|---|---|
| — | `pg_partman` availability | **Never a dependency.** Partition pre-creation is a scheduled task in `stats-writer` behind the D3 advisory lock — which N replicas per type require anyway | ADR-003 D1; T-012 unblocked |
| Q14a | Clock skew | **Reject at Input** when `ts.device > ts.server + stats.input.validation.max-clock-skew` (default `PT5M`). 400, field code `clock_skew_exceeded`, metric `records_clock_skew_rejected{type}`. Cross-field, so it is code in Input, not a schema rule | ADR-003 D9; input contract §2/§4; T-013 |
| Q3a | Topology declaration scope | **Broker-connected modules only** — Input and Writer. `stats-api` gets no broker connection. T-032 is the enforcement; the platform ack is a courtesy | ADR-002 §11 |
| — | DLQ retention and redrive | **No TTL, ever.** Alert on depth > 0 and oldest-message age; manual disposition within 14 days; redrive by an authorised ops role, per type, at ≤ 2× nominal, root cause first | ADR-002 §12; `amqp-topology-v1.md` §6; T-052 **BUILT 11 Sep 2026 (DL-138)** — `docs/runbooks/dlq-redrive.md` |
| Q5a | Volume contradiction | **115.2M records/day.** The internal "around 10 million" is withdrawn; it never blocked the schema | ADR-003; capacity tables |
| D5b | Late-arrival policy | **Engineering agreed**: skip, meter `records_expired_on_arrival`, ACK — **plus an alert when the counter is non-zero**. **SIGNED 4 Sep 2026 (DL-112); BUILT 10 Sep 2026 (T-026, DL-133); alert BUILT 19 Sep 2026 (T-050, DL-147).** | ADR-003 |

> **One thing this set deliberately did not do:** it did not close D5a. The RPO/RTO
> proposal — RPO ≤ 5 min, RTO 1 h per partition and 24 h for a full cluster restore —
> is engineering's input to a product and operations decision, not a substitute for it.
> **That signature was given on 4 September 2026 (DL-111) and T-051 is unblocked.**

### A3. Decisions of 4 September 2026

Nine open-questions rows closed: seven answers from ZC / Hasan, and the two signatures
that were owed. **Approver:** Naman Vashishtha. Full reasoning: `docs/decisions/DECISIONS.md`
DL-104…DL-112.

| Ref | Decision | Lands in |
|---|---|---|
| **Q15a** | **All three known `lorastats` dialects are accepted and normalised to one typed row.** Detection is **structural, never `version`**; one stored shape and one API shape, with the dialect as a metric label rather than a column; string coercion is range-checked against the signed `BIGINT` maximum *before* conversion and dead-letters on overflow; the enumeration is **closed at three**, so an unknown fourth dialect still dead-letters. **Supersedes DL-089 and reverses ADR-007 §4. Built 8 Sep 2026 — DL-127** | DL-105; ADR-007 Amendment §A1; **T-063 (done)** |
| Q15b | **`ts.device` is genuinely device-assigned.** Retention on `ts.device` is retention on measurement time, so the offline-backfill, late-arrival and redrive-age paths are all real | DL-106 |
| Q15c | **`_id` never reaches the wire** — a Mongo export artifact. No handling built; D14 stays the safety net | DL-107 |
| Q15d | **`status` can report a failed conversion.** Store, meter `records_upstream_status_not_ok{type}`, log at `WARN` — **never dead-letter**. Built 8 Sep 2026 — DL-128, which adds that an *absent* `status` is not counted | DL-108; **T-064 (done)** |
| Q13b | **Gateways jitter.** 13,333/s becomes configured headroom; verify with a `ts.server mod 600` histogram before sizing down | DL-104; amends DL-082 |
| Q13c | **2× replay cap agreed** — and still unenforceable by us | DL-109; T-049 unblocked |
| Q4b | **No IPv6, provisionally**, with a metered per-field rejection code as the reopening trigger | DL-110 |
| **D5a** | **SIGNED.** RPO ≤ 5 min; RTO 1 h per partition, 24 h full cluster | DL-111; **T-051 unblocked** |
| **D5b** | **SIGNED.** Skip, meter, ACK, alert | DL-112; **T-026 built 10 Sep 2026 (DL-133); the alert 19 Sep 2026 (T-050, DL-147)** |

## B. Approved implementation sequence

> **Phase numbering is shared with `docs/development/PROJECT_STATUS_AND_PLAN.md` §3 and is
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

**Entry criteria:** D-02 through D-08 are approved for the type being built, and the **decided half of D-10** — three images, per-type Writer deployment via `STAT_TYPE`, readiness probes — is in place. As of 29 Aug 2026 that is satisfied for `lorastats`. D-10's pending half is the runtime platform and release mechanics, which Phase 2 does not touch.

Build **exactly one type end to end** — `lorastats` — as the reference slice every other type is copied from. Fan out before it exists and you get five divergent implementations to reconcile.

Include validation, **no transformation** of the received record, publisher confirmation before any 2xx, durable per-type broker topology with three retry tiers and a DLQ, manual ACK after commit, `PRIMARY KEY (ts_device, uid)` with metered `ON CONFLICT DO NOTHING`, daily `RANGE (ts_device)` partitions with pre-creation, the `(tenant, clsbox, ts_device DESC, uid DESC)` index, retention keyed on `ts.device`, per-type metrics, and Testcontainers integration tests.

**Exit criteria:** a valid record lands in the table; an invalid **envelope** returns 400 and publishes nothing while an invalid **payload** is dead-lettered by the Writer (ADR-007 §5); broker down returns 503; database down grows the queue and eventually dead-letters; a duplicate `uid` is a harmless no-op **that increments `records_duplicate_suppressed`**; retention boundary tests pass on both sides.

> **Where the sequence stands, 8 September 2026.** Phase 2's happy path is built: the
> envelope gate, publisher confirms with the unroutable case closed, byte-identical
> publication, the auth seam, request limits, the partitioned table with pre-creation,
> the metered idempotent insert, manual ACK after commit, three retry tiers with a
> per-type DLQ whose hand-off is confirmed before the ACK, and one topology by
> construction (DL-118…DL-123). **Both halves of the exit criterion above are now
> real:** T-057 put the per-type payload gate in front of the write, so an invalid
> payload is genuinely dead-lettered rather than reaching the INSERT unvalidated
> (DL-126); T-063 accepted all three `lorastats` dialects into one closed enumeration
> normalised to one row (DL-127); and T-064 meters a non-OK upstream `status` without
> dead-lettering it (DL-128). **Phase 2 does not exit until** T-025/T-026/T-031
> (retention, late arrival, boundary), T-029/T-030/T-033 (poison, crash window, outage
> drill) and T-060 (DLQ alerting, now unblocked and sizeable against real volume) are
> done. **T-033 is Phase 3's entry criterion**, so it is the row that releases the
> fan-out. The ranked order, per owner, is `Stats_App_Tracker.xlsx` → `Next Up`.

### Phase 3 — Type fan-out

**Entry criteria:** Phase 2's reference slice exists and is green.

**State on 13 Oct 2026: DONE.** `sysstats` (19 Sep, DL-148), `omsstats` (21 Sep, DL-153), `ltestats` and `netstats` (8 Oct, DL-164); D-12 P3 answered 12 Oct (DL-167). Exit criterion: poison isolation is proved by `PoisonMessageIsolationIntegrationTest` and the three `<Type>PersistenceConsumerIntegrationTest`s for lorastats, sysstats and omsstats; `ltestats`/`netstats` are covered by `LteNetstatsPersistenceIntegrationTest` and `ops/drills/lte-netstats-smoke.py`, **not** yet by a cross-type poison test of their own.

`sysstats`, `omsstats`, `ltestats`, then `netstats` — each an **additive** copy of the Phase 2 pattern. *(`ltestats` schema reshaped on live data 18 Sep 2026, DL-146 — two closed shapes, `rsrp`/`rsrq` nullable; `netstats` had no records in the 18 Sep datastore query (Q15g), and 1,000 in the 9 Oct export — DL-165.)* **Order corrected 5 Sep 2026 (DL-125):** `ltestats` is blocked on D-12 P3 (whether `imsi` is stored masked or raw and whether it may be a predicate) and `sysstats` is not, so the first copy is rehearsed on the type nothing gates; `netstats` stays last as the `jsonb` hybrid. If any of them requires editing Phase 2 code, the abstraction is wrong and that is a design smell to raise, not to work around (STA-14A, STA-22).

**Exit criteria:** all approved types ingesting; the poison-message isolation test proves one bad type does not stall another.

### Phase 4 — API

**Entry criteria:** D-05 and D-09 are approved, the **decided halves** of D-10 (deployment shape, readiness probes) and D-12 (log hygiene, database roles) are in place, and **the OpenAPI contract is signed off by ZC** — which is the one thing genuinely gating this phase.

Implement Keycloak resource-server integration, token claim mapping, strict tenant-scoped repository queries, GET-only endpoints, deterministic pagination, response/error mapping, and contract tests. There is no tenant query parameter in the ordinary API unless a separately approved privileged access model exists.

*State on 13 Oct 2026: BUILT* (DL-162, 26 Sep) — ahead of ZC's sign-off by the approver's decision, so the sign-off criterion above is now a rework risk, not a gate. LTE/netstats readers since DL-164; `imsi` masked since DL-167; 10 s read statement timeout since DL-168. **Exit is held by the independent review of the isolation and security suites (T-028).**

*Since 21 Sep 2026 (DL-151) the contract has three operations, told apart by segment count: the tenant-wide list, the device-scoped `/{type}/{clsbox}/{from}[/{to}]` (T-067) and `/{type}/{uid}`. On the device paths a foreign or unknown `clsbox` is an **empty 200**, never 404 — a list, not a lookup (ADR-006 Amendment 21 Sep 2026). Problem `type` URIs use one spelling, underscores, as `ErrorCode.typeUri()` emits (DL-145).*

### Phase 5 — Operational readiness and release

**Entry criteria:** D-11 and D-12 are approved; the operations runbook and dashboards exist.

*State on 13 Oct 2026:* every Phase 5 build row exists — the DLQ redrive runbook (T-052, skip-and-continue since DL-168), graceful shutdown (T-053), DLQ and main-queue alerting (T-060, T-050), the end-to-end demo (T-069), the timed restore drill (T-051), the lock and detach measurements (T-054, T-055), dashboards (T-056), the burst load test (T-048) and the replay drill (T-049) — except **T-070**, one live Heimdal request, which waits on the unmerged basic-auth branch (T-071, DL-169). D-12's remaining questions (classification, network identifiers, log retention, audit) are still with security and compliance. ~~*State on 21 Sep 2026:* dashboards, the timed restore drill, the lock and detach measurements and the load test do not exist.*~~

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
