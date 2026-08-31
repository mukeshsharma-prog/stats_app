# Open questions and decisions — status and recommended answers

**Date:** 29 August 2026 · **Author:** Naman Vashishtha (SSE) · **Status:** working brief
**Audience:** lead engineer, product owner, platform
**Sources reconciled:** `Stats_App_Tracker.xlsx` (Open Questions, Decisions, Tasks),
`claude/CODE_START_CHECKLIST.md` §A, `claude/PROJECT_STATUS_AND_PLAN.md` §2.

> **Bottom line.** Only one item — ZC's confirmation of `docs/contracts/openapi.yaml` —
> meaningfully gates work. Five more were ours all along and are **decided in §2 of this
> document**. Everything else is a soft flag on a single task.

---

## 1. Scoreboard

| Sheet | Rows | Genuinely open |
|---|---:|---|
| Open Questions | 14 | 14 unresolved on the sheet — but 10 need someone else (ZC, product, ops, platform) and **4 were ours to decide and were sitting in the wrong sheet**. Those four are closed in §2. |
| Decisions | 28 | **2** — `D5a` (RPO/RTO, ESCALATED) and `D5b` (late arrival, NEEDS SIGN-OFF). `D5b` now has engineering agreement; product sign-off is still outstanding. |

Plus four residuals living *inside* already-approved ADRs, which neither sheet surfaces:

| Residual | Lives in | State after this document |
|---|---|---|
| Q3a — scope of "every module declares topology" | ADR-002 | **Closed** — §2.3 |
| Q13c — replay rate cap | ADR-005 | Open with ZC; unenforceable by us — §3.7 |
| Q8b-2 — Keycloak value confirmation | ADR-006 | Open with ZC; a config change — §3.8 |
| D12 — maximum query window | unwritten | Provisional pending the Phase 5 load test |

**Task impact:** 48 of 56 tasks carry no blocker at all. All of Phase 1 (T-001–T-009) and
20 of 24 Phase 2 tasks are clear to start today. After §2 the flagged count drops from 8
to 4. Full graph in §4.

---

## 2. Decided here — 29 August 2026

**Approver:** Naman Vashishtha. **Status:** DECIDED — implement as written. Each of these was
filed as a question for someone else and was never anyone else's to answer.

### 2.1 `pg_partman` availability — decided, not asked

**Decision.** Partition pre-creation is a **scheduled task inside `stats-writer`**, guarded by the
`pg_try_advisory_lock` that ADR-003 D3 already mandates. **`pg_partman` is never a dependency.**
If a managed instance happens to offer it, that is a convenience, not a design input.

**Why:**

* Managed PostgreSQL frequently does not expose the extension, so building on it imports a
  platform risk for no gain.
* The advisory lock is needed regardless — ADR-003 D8 deploys N writer replicas per stat type, and
  N replicas racing to create tomorrow's partition is exactly the failure the lock exists to prevent.
  Once the lock is there, the scheduled task is a few lines on top of it.
* Keeping the choice conditional is the only thing holding **T-012** in a flagged state.

**Consequence to accept:** we own the pre-creation logic and its failure mode. T-012's acceptance
criterion already covers this — a test that removes pre-creation must prove the failure is **loud**.

**Unblocks:** T-012, outright.
**Lands in:** ADR-003 — the body around
[ADR-003-Retention-and-Indexes.md:49](docs/architecture/adr/ADR-003-Retention-and-Indexes.md#L49)
and the residual row at
[ADR-003-Retention-and-Indexes.md:404](docs/architecture/adr/ADR-003-Retention-and-Indexes.md#L404),
which still reads `ACTION before Phase 2`.

### 2.2 Q14a — clock skew — reject at the edge

**Decision.** Reject at Input when `ts.device > ts.server + 300s`. Accept anything inside that
tolerance. Return **400** with a distinct error code, and **meter it**.

**Why — write this reasoning into the ADR:**

* Partition pre-creation is finite. A gateway with a 2038 clock either demands unbounded partition
  creation, or fails the insert deep inside the Writer where the caller can no longer be told
  anything.
* Rejecting at the edge is deterministic and testable, and it hands Heimdal a 4xx it can act on —
  consistent with rule 7 (invalid records are rejected and logged, never silently dropped).
* The tolerance exists because NTP drift of a few seconds is normal and must not be an outage.
  300 s is a starting value and is **externalised to `application.properties`**, like every other
  threshold in this project.
* The fixture already exists: `docs/schemas/fixtures/edge/<type>-clock-skew-future.json`. It moves
  from `edge/` to a defined expected outcome.

**This is an engineering call, not a product one.** It has been in the wrong queue since it was
raised.

**Lands in:** an ADR amendment (ADR-003 partition routing, cross-referenced from ADR-004 since the
check is on the envelope); `docs/schemas/SCHEMA_CATALOGUE.md` rows for timestamp plausibility and the
clock-skew fixture; a new Tasks row — see §5.1, the blocker link is currently missing entirely.

### 2.3 Q3a — topology declaration scope — courtesy ack, not a blocker

**Decision.** Read as *every **broker-connected** module* — `stats-input` and `stats-writer`.
**`stats-api` gets no RabbitMQ connection.** Declarations come from shared constants in
`stats-common` and must be identical and idempotent.

**Why:** the real enforcement is **T-032**, the declaration-conflict test, which proves at build
time what a platform sign-off would only assert in prose. Quorum queues sharpen this further — a
queue's type is fixed at declaration, so a mismatch is not a warning, it is a `RESOURCE_LOCKED`
startup failure.

Get the one-line platform acknowledgement. Do not wait on it. Reclassify from *blocker* to
*courtesy confirmation*.

### 2.4 DLQ retention and redrive authority — no TTL, ever

**Decision.** The DLQ has **no TTL**. A TTL'd dead-letter queue is silent data loss, which violates
non-negotiable rule 9 and the no-silent-error-handling rule outright.

| Control | Value |
|---|---|
| Alert | DLQ depth > 0, **and** oldest-DLQ-message age |
| Manual disposition | Expected within 14 days |
| Redrive authority | Authorised operations role only |
| Redrive scope | Explicit, per stat type — never blanket |
| Redrive rate | Capped at 2× nominal (2,667/s), the same cap as replay |
| Precondition | Root cause identified and resolved first. Blind automatic replay is not permitted |

**Trap to document in the runbook:** a message that has sat in the DLQ long enough becomes
`expired_on_arrival` the moment it is redriven, because its `ts.device` has aged past the 90-day
retention window. **A redrive after ~90 days is not a recovery — it is a no-op that increments a
counter.** Operators must know this before they attempt it, or a failed recovery will look like a
successful one.

**Unblocks:** T-052.

### 2.5 Q5a — volume contradiction — closed, the number is 115.2M/day

**Decision.** **115.2 million records per day across five stat types is the figure. Design against
it.** The internal "around 10 million" is withdrawn.

160,000 gateways × 6 reports/hour × 24 h = 23.04M per type per day; × 5 types = **115.2M/day**,
1,333/s average, **13,333/s** at the synchronised-burst design target.

**This does not block freezing the schema**, contrary to the sheet. Every schema decision is
identical at both volume figures — daily `RANGE (ts_device)` partitions, `BIGINT` counters,
`PRIMARY KEY (ts_device, uid)`, the `(tenant, clsbox, ts_device DESC, uid DESC)` index. The delta
between the two numbers is hardware, not rework.

**Action:** downgrade Q5a from HIGH and close it. Correct any document still carrying the 10M figure.

### 2.6 D5b — late arrival — engineering agreed, product sign-off still owed

**Engineering position: agreed as proposed.** A record whose `ts_device` predates the retention
window has no live partition. Skip the write, increment `records_expired_on_arrival`, ACK.

**Why it is the only implementable behaviour:** the alternative is writing rows into a partition
that does not exist. It discards only data that retention would delete minutes later, and the metric
makes the discard visible rather than silent.

**One addition to the proposal:** **alert when the counter is non-zero.** A systemic gateway backlog
and a harmless one-off look identical in the raw count; only the alert distinguishes them.

> **Still owed:** this is a deliberate data-loss-by-design path and needs **product sign-off in
> writing**. T-026 stays BLOCKED until it lands — engineering agreement is not the signature.

---

## 3. Needs ZC / product — with the default we commit to on silence

Every item carries a position. Send each with a date and an explicit
**"silence = confirmation by \<date\>"** clause.

### 3.1 Confirm `docs/contracts/openapi.yaml` — highest leverage on the list

**Position:** send for confirmation with a hard *silence = confirmation by \<date\>* clause.

This is the **only** item that meaningfully gates work. Nine tasks sit behind it — T-038 directly,
and T-039 → T-046 transitively, which is essentially all of Phase 4. ZC's own answer to C2 was
*"please create an OpenAPI definition and we will confirm/decline it"*, so the ball has been in our
court since 21 August and the artifact exists. What remains is a reply, not a deliverable.

Write contract tests against the draft meanwhile — the risk on a GET-only contract is low, and it
makes the confirmation a diff rather than a start.

### 3.2 Q13b — gateway report jitter — stop treating it as a question

**Position:** keep designing for the synchronised worst case (13,333/s), but **stop waiting on ZC.
This is measurable, not askable.**

A histogram of `ts.server mod 600` over 24 hours answers it completely — from Heimdal's
telemetry, or from our own first day of real ingest.

**Meanwhile:** keep prefetch, concurrency and replica count as configuration properties, so the
answer lands as a property change rather than a rewrite. **Defer the hardware purchase, not the
design.** This question is worth 10× on hardware and nothing at all on code.

### 3.3 Q8a-2 — Heimdal inbound auth — propose OAuth2, set a date

**Position:** **OAuth2 client credentials** as the default.

Keycloak already exists in the estate. A `heimdal-ingest` client in the `statsapp` realm carrying
the `stats.ingest` role costs nothing new to stand up, and credential rotation is a solved problem
there. Choose mTLS only if the platform already runs a certificate authority both sides trust —
otherwise it is new infrastructure to answer a question OAuth2 already answers.

**Send as:** *"Unless you object by \<date\>, we implement OAuth2 client credentials."*

**Task impact: soft.** T-017 builds the seam — `stats.input.auth.mode=none|mtls|oauth2`, **no
default in code**, `WARN` on every `none` startup, and a test that refuses to start under `prod`.
The mechanism plugs in later.

> **Risk accepted, not resolved:** until this closes, anything that can reach the Input port can
> write statistics. **Must close before any shared deployment.**

### 3.4 D5a — RPO / RTO — propose numbers, do not wait for them

**Proposal:**

| Target | Value | Basis |
|---|---|---|
| RPO | **≤ 5 minutes** | Continuous WAL archiving to object storage — cheap, and the correct lever here |
| RTO — single partition | **1 hour** | The realistic recovery unit under ADR-003 D5 |
| RTO — full cluster restore | **24 hours** | Deliberately loose |

**Why RPO is tight and RTO is loose:** lost data here is **unrecoverable**. Heimdal retries only on
a non-2xx, so anything we acknowledged with a 200 and then lost is gone — there is no upstream copy
to re-request. Downtime, by contrast, delays reading 90-day telemetry. That is an inconvenience, not
a loss.

**Framing for product:** *"If we lost the last 15 minutes of statistics, or took 8 hours to come
back — what actually breaks?"* Ask what breaks before anyone sets a target reflexively; at
multi-terabyte scale a tight RTO may simply not be purchasable.

**Task impact:** T-051 (partition restore drill) is hard-blocked until this lands.

### 3.5 Q13c — replay cap — ask, and record that we cannot enforce it

**Position:** request the 2× nominal cap (2,667/s), and record explicitly that **we have no
mechanism to enforce it.**

Our actual defence is 503 backpressure plus prefetch. T-018's configurable rate limit is the one
control we genuinely own. The cap belongs in the replay runbook as an agreement, not in the
architecture as a mechanism. A 503 during replay is the backpressure channel working, not an
incident — say so in the runbook, or someone will page on it.

### 3.6 Q8b-2 — Keycloak values — no change, set a deadline, proceed

**Position:** nothing to do. Every value is a property with **no default in code**; ZC's
confirmation is a config edit, not a code change. Set a deadline and keep building. The proposed
realm is already live in dev.

### 3.7 Q4b — IPv6 — let production answer it

**Position:** keep the `netstats` schema **strict IPv4-only**, but ensure the rejection produces a
**distinct, metered error code per field**, so an IPv6-reporting gateway surfaces as a visible
validation-failure spike rather than as silence.

If IPv6 gateways exist we will see them within a day of go-live. One `$def` widens if the answer is
yes; no code change either way.

### 3.8 API consumer catalogue — do not block on it

**Position:** ship the two operations now. Adopt the standing rule that **any future index must
trace to a named consumer**. Ask product for consumer names as a **Phase 4 entry criterion**, not a
Phase 1 blocker.

---

## 4. What actually blocks the tasks

48 of 56 tasks carry no blocker. The real dependency graph:

| Blocker | Tasks affected | Severity |
|---|---|---|
| **`openapi.yaml` confirmation** | T-038, and transitively T-039 → T-046 — **9 tasks, essentially all of Phase 4** | **Hard. The long pole.** |
| D5b late arrival (product sign-off) | T-026 | **Hard** — marked BLOCKED |
| D5a RPO/RTO | T-051 | **Hard** — marked BLOCKED |
| `pg_partman` | T-012 | ~~Soft~~ — **dissolved by §2.1** |
| DLQ retention policy | T-052 | ~~Soft~~ — **dissolved by §2.4** |
| Q8a-2 inbound auth | T-017 | Soft — build the seam, the mechanism plugs in later |
| D-03 request limits | T-018 | Soft — pick values; they are properties |
| Q13c replay cap | T-049 | Soft — a drill, not code |

**Before this document: 8 tasks flagged. After §2: 4** — three genuinely hard (the OpenAPI
confirmation, D5a, D5b) and one soft flag that never stopped anyone writing code, plus
T-018's request limits and T-049's replay-cap drill, neither of which blocks code.

The tracker has been updated to match: T-012 and T-052 have had their *Blocked by* cells
cleared, T-026 and T-051 stay `BLOCKED` with the reason sharpened, and T-013, T-027,
T-050, T-002, T-032 and T-038 carry the new acceptance criteria.

---

## 5. Tracker reconciliation — and a correction to this document

> **Correction, recorded rather than quietly amended.** The first version of §5 reported
> that D-10 and D-12 were missing from the Open Questions sheet and that Q14a had no task
> reference. **Both had already been fixed**, in the 26–27 August saves of
> `Stats_App_Tracker.xlsx`. The analysis behind those two findings was run against a copy
> of the workbook read earlier in the session, before that save landed. The third finding
> — that `CODE_START_CHECKLIST.md` contradicted itself about D-10 — was real, and is
> independent of the sheet.

### 5.1 Q14a — was already referenced; now closed

The sheet's Q14a row named "Writer partition routing", and `Tasks!N14` and `Tasks!N23`
already carried it as a **soft** input to T-013 and T-022 respectively — correctly, since
neither task could start the skew check before the decision existed. Four working
documents also listed it as open
([claude/CLAUDE.md:294](claude/CLAUDE.md#L294),
[claude/IMPLEMENTATION_RULES.md:108](claude/IMPLEMENTATION_RULES.md#L108),
[claude/PROJECT_STATUS_AND_PLAN.md:209](claude/PROJECT_STATUS_AND_PLAN.md#L209),
[claude/DEVELOPMENT.md:637](claude/DEVELOPMENT.md#L637)).

§2.2 closes it, and the acceptance criterion is now written into **T-013** so the
behaviour gets built rather than evaporating with the question.

**Trap 16 was the real find here**, and it was already on the sheet: the five clock-skew
fixtures sit in `edge/`, and both oracles read every `edge/` fixture as expected-valid —
so the open question already had **accept** baked into ten green assertions. The trap's
proposed remedy (move them to `invalid/` and tighten the envelope schema) **is not
buildable**: the rule compares `ts.device` against `ts.server`, and JSON Schema 2020-12
cannot compare two instance locations. Moving them would make the CI gate fail on a
correct schema. What was done instead: the files stay in `edge/`, stay schema-valid, and
each carries `$expect: "input-reject"` with `$expectCode`, which `validate-fixtures.py`
now asserts and prints. The gate went from 71 to **76 assertions** — 71 schema cases plus
5 markers. *(Later the same day, ADR-007 took it to **87** — 80 schema cases plus 7 markers,
the 2 new ones being `$expect: "writer-dlq"`.)* The Java parity test still expands to the
schema cases only, and that gap is
deliberate and documented in `MANIFEST.md`.

### 5.2 D-10 and D-12 — the checklist contradicted itself, and now does not

Both were already on the Open Questions sheet (rows 16 and 17, added 26 Aug). The real
defect was in `claude/CODE_START_CHECKLIST.md`, which carried both as blanket
`PENDING APPROVAL` while its own Phase 2 entry criteria cited D-10 as satisfied:

* [line 18](claude/CODE_START_CHECKLIST.md#L18) — D-10 Deployment/scaling
* [line 20](claude/CODE_START_CHECKLIST.md#L20) — D-12 Data handling
* [line 72](claude/CODE_START_CHECKLIST.md#L72) — Phase 2 entry requires D-10 approved, *and* declares Phase 2 entry satisfied for `lorastats`
* [line 90](claude/CODE_START_CHECKLIST.md#L90) — Phase 4 entry requires D-10 and D-12

That mattered because §A's own rule is *"a developer may implement only the work that does
not rely on an unanswered row."* Taken literally, Phase 2 was both open and blocked.

**Resolved by splitting each row**, rather than by picking a winner — because both halves
were true of different things:

| Row | Decided | Still pending |
|---|---|---|
| **D-10** | Three images, one per module (Q2); per-type Writer deployment via `STAT_TYPE` (ADR-003 D8); separate liveness/readiness probes (D13); env-var configuration (ADR-001) | Runtime platform, image registry, promotion path, resource requests/limits — **platform engineering** |
| **D-12** | DLQ retention — no TTL, with alerting and authorised per-type redrive (ADR-002 §12); no payloads on the success path, no tokens in logs (D1); database roles `stats` / `stats_ro` / none-for-Input (ADR-006) | Data classification, IMSI masking, log retention, encryption at rest, audit evidence — **security + compliance** |

The Phase 2 and Phase 4 entry criteria now cite the decided half explicitly. Phase 4's
one genuine gate is ZC's confirmation of the OpenAPI contract.

### 5.3 Q5a was over-rated — now closed

Marked HIGH and *"blocks freezing the schema."* Per §2.5 it does not. Closed at
115.2M/day and removed from the blocking set.

### 5.4 A contract defect, fixed rather than filed

The sheet's row 18 recorded a real defect found on 26 Aug: `openapi.yaml` mapped *"no
usable tenant claim"* to **401**, while ADR-006 mandates **403** and T-039/T-045 are
written to 403. **Fixed on 29 August 2026** — the `Unauthorized` description, the
`Forbidden` description, the error catalogue and the `ProblemForbidden` example all now
say 403 for an absent or empty tenant claim, and 401 means an authentication failure only.

This had to be fixed *before* the contract goes to ZC. Freezing a contract that
contradicts the security ADR would have put the defect beyond easy reach.

## 6. Next actions

| # | Action | Owner | When |
|---:|---|---|---|
| 1 | Send `openapi.yaml` to ZC with a *silence = confirmation by \<date\>* clause | Lead | **Today — this is the long pole** |
| 2 | ~~Write §2.1–§2.6 into the owning ADRs, contracts, requirements, working docs and the tracker~~ | Lead | **DONE 29 Aug 2026 — see §7** |
| 3 | Send the consolidated ZC message: Q13b, Q8a-2, Q13c, Q4b | Lead | This week |
| 4 | Put D5a to product with the framing question in §3.4 | Lead + product | This week |
| 5 | Get D5b signed **in writing** — T-026 stays BLOCKED until then | Product | This week |
| 6 | ~~Reconcile the tracker~~ | Tracker owner | **DONE 29 Aug 2026** — statuses, blockers, six new Decisions rows, traps 17–18, seven change-log rows, and the stale table ranges repaired |
| 7 | ~~Split D-10 and D-12 in the checklist~~ | Lead | **DONE 29 Aug 2026** — see §5.2 |
| 8 | ~~Add the Q14a rejection to T-013's acceptance criteria~~ | Lead | **DONE 29 Aug 2026** |
| 9 | Ask platform for the one-line Q3a acknowledgement (courtesy — do not wait) | Lead | Whenever |

---

## 7. Where these decisions must land

§2 is not implemented until it appears in the documents that govern the code — otherwise it lives
only in this file, which is exactly the failure mode `PROJECT_STATUS_AND_PLAN.md` §0b records for
the ARD items that were "adopted without conflict" on 18 Aug and then propagated nowhere for six
days. **All of it was propagated on 29 August 2026; this table is the record of where.**

| Decision | Landed in |
|---|---|
| §2.1 `pg_partman` | **ADR-003 D1** (rewritten) and its decisions register; `stats-writer/application.properties`; tracker T-012 unblocked |
| §2.2 Q14a clock skew | **ADR-003 D9** (new section); **SRS `STA-03B`** (new requirement); `heimdal-input-contract-v1.md` §2 response matrix and §4; `SCHEMA_CATALOGUE.md`; `fixtures/MANIFEST.md`; the five `edge/` fixtures (`$expect`/`$expectCode`); `validate-fixtures.py` (71 → 76 assertions); `SchemaFixtureParityTest` javadoc; `stats-input/application.properties`; T-013 acceptance criteria; the four working docs |
| §2.3 Q3a | **ADR-002 §11** (new section) and its decisions register; `CLAUDE.md`, `PROJECT_STATUS_AND_PLAN.md`, `DEVELOPMENT.md`, `IMPLEMENTATION_RULES.md`, `README.md`, `ANSWER_RECONCILIATION.md`; `CODE_START_CHECKLIST.md` D-06 |
| §2.4 DLQ policy | **ADR-002 §12** (new section); `amqp-topology-v1.md` §6 (rewritten); **SRS `STA-12A`** extended; ARB DLQ row; BRD `BR-34`; `stats-writer/application.properties` (placeholder property removed); `SKILLS.md` Skill 4; T-050 and T-052 |
| §2.5 Q5a / 115.2M | `CLAUDE.md` §7 and the historical-docs table; `PROJECT_STATUS_AND_PLAN.md` §2c; `CODE_START_CHECKLIST.md` D-11; `DEVELOPMENT.md`; ARB capacity paragraph |
| §2.6 D5b agreement | **ADR-003** late-arrival section and register; `fixtures/MANIFEST.md`; `2B-ANSWERS`; `IMPLEMENTATION_RULES.md`; T-026 stays BLOCKED pending the product signature |
| §5.4 401 → 403 | `docs/contracts/openapi.yaml` — `Unauthorized`, `Forbidden`, the error catalogue and `ProblemForbidden` |
| All six | `CODE_START_CHECKLIST.md` **§A3** (new gate table); `CLAUDE.md` **v1.6** header; tracker Decisions (6 new rows), Open Questions, Tasks, Traps (17–18) and Notes (7 rows) |
