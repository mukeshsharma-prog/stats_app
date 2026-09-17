# CLAUDE.md

Project context for AI-assisted development on the **Stats App**.
This file lives in `claude/` together with every other working document.
It is still auto-loaded every session: the repository-root `CLAUDE.md` is a
pointer file whose only content is an `@claude/CLAUDE.md` import. Edit this file,
not the pointer.

> **Version 1.12 — 12 September 2026.** No decision in 1.11 changes. What changes is
> that **the four Phase 1/2 rows that were PARTIAL are DONE, the DLQ has a redrive, and the
> platform's own API guide has been read against this project.** Governing entries:
> **DL-134…DL-138** (T-005, T-009, T-027, T-052) and **DL-139** (the reconciliation).
>
> **Changed in 1.12 — re-read these:**
>
> * **`maintenant` moves with `tenant` and equals it.** When a device changes tenant on
>   the Datahub, *both* fields are set to the new tenant name (Naman, 12 Sep 2026, from
>   live behaviour). So they are equal in production, not only in the sample data — which
>   is exactly why the trap is dangerous: **no real record can distinguish a filter on the
>   wrong field from a filter on the right one.** The isolation rule is untouched —
>   `tenant`, only ever `tenant` — and the fixture rule (make them differ) is the *only*
>   test that can catch the mistake. §4 and §6 corrected. DL-139.
> * **Two questions added, none answered.** **Q8a-3:** is the stats push a Heimdal
>   *ForwardRule* — `{url, type, id, maxRetry}`, "credentials in the URL"? If so, the auth is
>   HTTP basic, which `stats.input.auth.mode` cannot express, and Heimdal's retry is
>   **bounded by `maxRetry`**, not indefinite as rule 8 assumes. **D16:** a gateway that
>   changes tenant keeps its history under the old `tenant`; ~~recommended position is *yes,
>   that is correct*, to be recorded as an ADR-006 amendment~~ — **ratified 17 Sep 2026
>   (DL-142), ADR-006 amended; only Q8a-3 remains open.** §7. DL-139.
> * **Logging is a contract, not a convention (T-005, DL-134).** Input mints a `traceId`,
>   returns it as `X-Trace-Id` and carries it in AMQP `correlationId`; `uid` rides in
>   `messageId`; the Writer restores both *before* parsing. `StructuredLoggingContractTest`
>   requires an `event` key on every application log statement, and the throwable guard
>   now sees the fluent API too — `log.atError()…setCause(e)` fails the build.
> * **`stats_ro` cannot write, and PostgreSQL says so (T-009, DL-135).** `INSERT`,
>   `UPDATE`, `DELETE`, `TRUNCATE`, `CREATE` and sequence access all fail with SQLSTATE
>   `42501` in `DatabaseRolesIntegrationTest`; Compose blanks the database variables in
>   `stats-input` and hides each module's identity from the others. Rule 2 is enforced by
>   the database, not by review.
> * **The Writer is observable (T-027, DL-136, DL-137).** `records_consumed`,
>   `records_persisted`, `records_processing_duration`, `records_postgres_errors`, plus
>   polled-and-cached `queue_depth`, `dlq_depth` and `consumer_availability` with a
>   success flag and last-success timestamp — a failed poll writes **NaN**, never zero.
>   Contract: `docs/contracts/writer-metrics-v1.md`. The branch's own retention cutoff and
>   polled lag gauge were **dropped on merge** because DL-132/DL-133 already decided both.
> * **The DLQ has a redrive, and a redriven record gets exactly one attempt (T-052,
>   DL-138).** `./gradlew :stats-writer:redriveDlq` — per type, ≤ 2,667/s, change-id and
>   `root-cause-resolved=true` required, refuses a record past the retention window, and
>   the copy is confirmed before the original is ACKed. `x-stats-manual-redrive=true`
>   sends a repeat failure straight back to the DLQ with no fresh tiers. Runbook:
>   `docs/runbooks/dlq-redrive.md`.
> * **What to do next:** **T-060 and T-050** — every counter and both queues are real,
>   the redrive exists, and nobody is watching. Then **T-029/T-030/T-033**, which is what
>   lets the fan-out start at **`sysstats`**. T-051, T-054 and T-055 are unblocked.
>
> **Version 1.11 — 10 September 2026.** No decision in 1.10 changes. What changes is
> that **a record now leaves again as well as arriving**: retention is built, and so is
> the late-arrival path it implies. `stats-writer` is feature-complete for `lorastats`.
> Governing entries: **DL-132, DL-133** (T-025, T-026), plus a renumbering: **DL-131**.
>
> **Changed in 1.11 — re-read these:**
>
> * **Retention is `DETACH PARTITION … CONCURRENTLY` then `DROP TABLE`, never row
>   deletion.** One daily maintenance pass per type — pre-create **then** retain, in
>   that order — behind the same `pg_try_advisory_lock`, staggered by an offset derived
>   from the stat type. `stats.retention.default-days=90` is finally *read*. DL-132.
> * **Every run begins by repairing the last one, and this is the finding that shaped
>   the design.** An *interrupted* `DETACH CONCURRENTLY` leaves the partition pending
>   detach, and from then on **every** detach on that table fails — while `INSERT` and
>   pre-creation keep working. Retention stops permanently, ingestion looks perfectly
>   healthy, and the only symptom is disk. It is now **window 14** in `FLOW.md` §8, and
>   it is silent. The sweep also reclaims tables an earlier run detached but never
>   dropped, counted separately as `retention_orphans_dropped{type}` — an ordinary drop
>   counter would read as retention working.
> * **A late-arriving record is skipped, metered and ACKed — never dead-lettered.** The
>   record is *valid*; a dead-letter would sit in a queue that never expires and be
>   re-rejected on every redrive, because the record can only get **older**.
>   `RetentionGuard` therefore **returns a decision where `PayloadGate` throws**: this
>   path has to end in a broker ACK, and an exception cannot produce one. DL-133.
> * **The guard and the sweep read ONE cutoff.** `RetentionWindow` is the single
>   definition. Two calculations that drifted by a day would admit a record into a
>   partition the sweep had just dropped — insert fails, record dead-letters, every
>   redrive re-derives it forever. The boundary belongs to the **retained** side on
>   both sides of the comparison, because over-retaining costs a partition and
>   under-retaining deletes data nobody reports.
> * **Per-type retention is now `stats.retention.days-by-type.<wire-name>`**, not the
>   recorded `stats.retention.<type>-days`. The map is validated against
>   `StatType.wireName()` at startup, so a typo fails the context instead of silently
>   reverting that type to 90 days. `default-days` has **no default in code**.
> * **The fixture gate learned a fourth marker.** `writer-skip` marks a record the
>   catalogue admits, Input publishes, and the Writer deliberately does not store. The
>   five `<type>-late-arrival.json` fixtures previously said the record is *"DLQ'd
>   without retry"* — the opposite of the signed policy — and carried **no marker at
>   all**, so a signed data-loss path had no assertion anywhere. 91 → **96** assertions,
>   7 → **12** markers, 84 Java parity cases unchanged, no fixture added or moved.
> * **`claude/DECISIONS.md` had two entries numbered DL-130.** The API-examples entry is
>   renumbered **DL-131**; every external citation resolved to the other one, so nothing
>   else changed.
> * **What to do next:** **T-029/T-030/T-033**, which is what lets the fan-out start at
>   **`sysstats`**. **T-060 and T-050** are the gap this change leaves: both queues and
>   both counters are real and **nobody is watching either**. T-051, T-054 and T-055 are
>   unblocked — they measure what T-025 built.
>
> **Version 1.10 — 8 September 2026.** No decision in 1.9 changes. What changes is
> that **the Writer's failure arm is no longer idle** and **`lorastats` is finished
> end to end**. Governing entries: **DL-126, DL-127, DL-128** (T-057, T-063, T-064).
>
> **Changed in 1.10 — re-read these:**
>
> * **The payload gate is built, and it cannot be switched off.** `PayloadGate` is
>   type-agnostic, parses the body once, and hands the writer a `ValidatedRecord` —
>   so validation is a *signature*, not a convention. A failure throws
>   `PermanentFailureException` and reaches the DLQ on the **first** attempt, skipping
>   all three tiers. `stats.writer.validation.intended-scope` is **deleted**, not
>   promoted: ADR-007 §5 offers no second scope, and a key that can turn the gate off
>   is a one-line change away from disabling a data-safety control. `schema_version`
>   now comes from the validator's own result. DL-126.
> * **All three `lorastats` dialects are built.** The schema is a **closed `oneOf` of
>   three**; `LorastatsDialects` detects structurally and normalises before the column
>   mapping — which is the ordering that matters, because taking `data_extra` from the
>   wire shape instead would dump fifteen `lora_*` members into it on every dialect-C
>   record and leave the typed columns NULL. **~15M msg/day have stopped
>   dead-lettering.** DL-127.
> * **Dialect C is NOT "everything prefixed".** The production record carries fifteen
>   `lora_`-prefixed counters alongside a **bare** `fsk_received` and a **bare**
>   `timespan`. Read `valid/lorastats-dialect-prefixed.json` before writing anything
>   against it; a parser written from the prose rejects the traffic it was written to
>   accept.
> * **A non-OK upstream `status` is metered, stored, and never dead-lettered** —
>   `records_upstream_status_not_ok{type}` plus a `WARN`. **An *absent* `status` is not
>   counted**: "upstream did not say" and "upstream said it failed" are different
>   facts, and DL-108 read literally would have lit the counter on ordinary traffic.
>   DL-128.
> * **The fixture gate learned a third marker.** `writer-range-reject` marks a record
>   the catalogue **admits** and the Writer refuses on the `BIGINT` range check — the
>   one boundary no regex can express. 88 → **91** assertions, 81 → **84** Java parity
>   cases, 76 → **79** fixture files. The marker count stayed at seven while its
>   composition changed completely.
> * **What to do next:** **T-025/T-026/T-031** (retention, late arrival, boundary),
>   then **T-029/T-030/T-033**, which is what lets the fan-out start at **`sysstats`**.
>   **T-060 is unblocked** and can now be sized against real dead-letter volume.
>
> **Version 1.9 — 5 September 2026.** No decision in 1.8 changes. What changes is
> that **most of the `lorastats` path is now code**, and four rules below are now
> enforced by tests that fail the build rather than by this document asking nicely.
> Governing entries: **DL-113…DL-124**.
>
> **Changed in 1.9 — re-read these:**
>
> * **The ingest path is real.** A 2xx follows the publisher confirm and nothing
>   else; an acked-but-*returned* message is a **failed** acceptance (503 +
>   `records_unroutable{type}`); publication is byte-identical, proved by a fixture
>   built to fail under any re-serialisation. The auth **seam** exists —
>   `stats.input.auth.mode` has no default in code and `prod` refuses `none` — and the
>   endpoint is bounded (256 KB → 413, 4,000 req/s per instance → 429). DL-118…DL-121.
> * **The Writer's failure arm is real.** Three retry tiers, a per-type DLQ that
>   expires nothing, and **the onward copy is publish-confirmed before the original is
>   ACKed**. It is built and *idle*: nothing yet produces a permanent failure, which is
>   T-057. DL-122.
> * **The two broker-connected modules cannot disagree about topology.**
>   `AmqpTopology` owns the complete declaration — durability and auto-delete as well
>   as names and arguments — and one assertion checks both. DL-123.
> * **`clsbox` is stored in ONE canonical case** — uppercased by the Writer, with
>   `CHECK (clsbox ~ '^[0-9A-F]{12}$')` as the table's own backstop. The envelope schema
>   still accepts either case, so a lowercase record is never 400'd. The API filter is
>   `clsbox = upper(:clsbox)`, never `upper(clsbox) = …`, which would stop the index
>   being used. **Every new stat-type table must carry the CHECK — like `data_extra`.**
>   DL-115.
> * **Two guards now fail the build, and both were written before the code they
>   constrain.** `VersionIsNotADiscriminatorTest` fails if any main source outside a
>   three-entry whitelist names the bare identifier `version` (DL-113).
>   `WriterLogsCarryNoThrowableTest` plus `SafeFailure` make it impossible for a
>   throwable — and therefore a payload value — to reach a `stats-writer` log line
>   (DL-124). The implemented rule is **stricter** than adopted guard P2, because
>   "type and message, never `DETAIL`" cannot be built.
> * **The API contract is linted in CI**, not merely parsed: `redocly lint` pinned at
>   2.51.2, before the Gradle build. DL-117.
> * **The inlined OpenAPI data schemas are checked against the validation catalogue.**
>   The gate resolves local references, compares validation-relevant keywords for all
>   five types, and explicitly verifies the Lorastats canonical projection and the
>   Netstats interface-pattern adaptation. Its mutation tests prove drift fails CI.
>   DL-140.
> * **The non-Markdown files caught up.** Seven artifacts still stated the reversed
>   ADR-007 §4 — a live properties file, the schema catalogue, the fixture manifest and
>   the API contract among them. DL-116. Note what that decision preserves: **DL-105
>   changed the decision, T-063 changes the behaviour, and T-063 is not built.** Today
>   only the bare/integer dialect validates. ~~Superseded by 1.10 — all three
>   validate since 8 Sep 2026 (DL-127).~~
> * **What to do next is now a sheet.** `Stats_App_Tracker.xlsx` → **Next Up**, ranked
>   across both owners with the gate on each. ~~Short version: **T-063**, then T-057,
>   then T-064~~ — **all three were done on 8 Sep 2026; see 1.10 above for the current
>   order.** T-060 waits for T-063; the fan-out starts at **`sysstats`**, not
>   `ltestats`, and starts when T-033 is green.
>
> **Version 1.8 — 4 September 2026.** Closes **nine** open questions against the
> answers ZC and Hasan returned, and the two signatures that were owed. Governing
> entries: **DL-104…DL-112**; ADR-007 gains an **Amendment — 4 September 2026**.
>
> **Changed in 1.8 — re-read these:**
>
> * **All three `lorastats` dialects are now ACCEPTED, not dead-lettered.** Q15a was
>   answered *"make it work for all the known dialects."* **DL-089 is superseded by
>   DL-105** and ADR-007 §4 is reversed. Four rules bound it: detection is
>   **structural, never `version`**; all three normalise to the **same typed
>   columns** (the dialect is a metric label, not a column); string coercion is
>   **range-checked against `BIGINT` before conversion** and dead-letters on
>   overflow; and the enumeration is **closed at three** — an unknown fourth dialect
>   still dead-letters. T-063.
> * **`ts.device` is genuinely device-assigned** (Q15b, DL-106). The offline-backfill
>   trap, the late-arrival policy and the redrive-age trap all describe **real**
>   paths.
> * **`status` can report a FAILED conversion** (Q15d, DL-108). It is **stored**,
>   **metered** as `records_upstream_status_not_ok{type}` and logged at `WARN` —
>   never dead-lettered. It is the one passthrough member that is now *read*, though
>   still never branched on.
> * **`_id` never appears on the wire** (Q15c, DL-107) — it is a Mongo *export*
>   artifact of Heimdal's own store. That is not permission to reintroduce Mongo here.
> * **Gateways jitter across the 10-minute cycle** (Q13b, DL-104). Expected steady
>   state is ~1,333/s; DL-082's 13,333/s is now **configured headroom**, and hardware
>   is not cut until the `ts.server mod 600` histogram confirms it.
> * **D5a and D5b are SIGNED** (DL-111, DL-112). RPO ≤ 5 min / RTO 1 h per partition,
>   24 h full cluster; late arrival is skip-meter-ACK-alert. **T-051 and T-026 are
>   unblocked.**
> * **Heimdal agrees the 2× replay cap** (Q13c, DL-109) — and we still cannot enforce
>   it, so every defence stays. **No IPv6 "for now"** (Q4b, DL-110), with a metered
>   per-field rejection code as the reopening trigger.
>
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
>   discriminate them. ~~We encode one and dead-letter the rest.~~ **Superseded by
>   v1.8 (DL-105): all three are accepted and normalised to one row.** The half that
>   survives is that `version` is never the discriminator.
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
| **maintenant** | The *maintaining* tenant. **Set to the same value as `tenant`, and moved with it:** when a device changes tenant on the Datahub, both fields take the new tenant name (DL-139). Equal in the sample data **and in production** — **this is a trap, see §6**. Never used for isolation. |
| **clsbox** | **The device identifier field.** 12 hexadecimal characters. This is the name on the wire, in the schema, in Java, in the PostgreSQL column, and in API responses (ADR-007 §1). The SRS called it `gateway`; nothing in production ever has. |
| **gateway** | The *physical device* — used in prose only ("160,000 gateways", "a gateway with a bad clock"). **Never a field name.** Do not mechanically rewrite this word to `clsbox`. |
| **ts.server** | The canonical **arrival** clock — Heimdal's receipt time. What the SRS called `ts.processor`. Clock skew is measured against it (ADR-007 §3). |
| **ts.device** | When the gateway measured the record. **Retention keys on this**, not on `ts.server` (STA-23B). |
| **Passthrough members** | `ts.datastore`, `ts.converter`, `ts.expiresOn`, `status`, `version` — accepted, stored, returned verbatim, and **never branched on**. Two are *read* for metering only and change no routing: `ts.converter` (ADR-007 §2) and, since DL-108, `status` — anything other than exactly `["conversion ok"]` increments `records_upstream_status_not_ok{type}`. `version` is read by nothing, ever: it is not a dialect discriminator. |
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
    and keys on `ts.device`.** The mechanism is `DETACH PARTITION … CONCURRENTLY`
    followed by `DROP TABLE` — **never row deletion** — once daily per type, behind
    `pg_try_advisory_lock`. `stats.retention.default-days` has **no default in code**:
    retention deletes data, so an unconfigured deployment must fail to start rather
    than adopt a window somebody guessed. A record that arrives already past the window
    is skipped, metered as `records_expired_on_arrival{type}` and **ACKed — never
    dead-lettered**, because the record is valid and a redrive re-derives the same
    verdict forever. The cutoff the sweep drops on and the cutoff the guard enforces are
    **one function**. *(STA-23, STA-23A, STA-23B, STA-23C; ADR-003 D1/D3/D4;
    DL-132, DL-133)*
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
**differ**. This is the single highest-risk item in the project. **DL-139 made the
reason precise:** on the Datahub a tenant change sets *both* fields to the new name, so
they are equal on every real record, in production as in the samples. That means **no
production record can ever reveal a filter on the wrong field** — the only place the
two values differ is a fixture we wrote on purpose, which is why that fixture rule is
not optional.

**Head-of-line blocking with a shared queue.**
If all stat types share one queue, a poison message in one type stalls every
other type. The per-type queue structure is not decoration — it is why C1 was
escalated and answered. There must be a test proving a poison message in one type
does not delay another.

**Retention keys on `ts.device`, and that has a consequence.**
A gateway that was offline delivers records whose `ts.device` is weeks older than
`ts.server`. Those records are near expiry the moment they arrive. This is the
**answered and accepted business rule** — do not "fix" it by quietly switching to
`ts.server`. Document it and cover it with boundary tests. **Confirmed real on
4 Sep 2026 (Q15b, DL-106):** `ts.device` is genuinely device-assigned. The 15-record
sample showed `ts.device == ts.server` on 14 of 15 records only because all fifteen
came from healthy gateways sending immediately — a sample of the healthy path is not
evidence about the absence of the unhealthy one.

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
bloat, long transactions, and lock contention. Time partitioning was evaluated and
chosen: since **T-025** retention is `DETACH PARTITION … CONCURRENTLY` then
`DROP TABLE`, and nothing in this codebase deletes a row for retention. If you find
yourself writing a bounded batch loop, the design has been misread.

**An interrupted `DETACH CONCURRENTLY` stops retention for good, and nothing looks
wrong.**
Cancel one — a rolling deploy, an OOM, a `statement_timeout` — and PostgreSQL leaves
the partition *pending detach*. From then on **every** `DETACH CONCURRENTLY` on that
table fails with `partition "…" already pending detach`, **while `INSERT` and partition
pre-creation carry on working perfectly.** Ingestion is healthy, no queue backs up, no
counter moves, and the only symptom is that disk stops going down. Every run now begins
with `DETACH … FINALIZE` (DL-132), so it self-heals on the next cron; the flag to look
at by hand is `pg_inherits.inhdetachpending`. Two related edges: the statement **cannot
run inside a transaction block** at all — it is a hard `ERROR`, so anything that wraps
the sweep in a transaction breaks it outright — and between the detach and the drop the
table still exists holding its rows, so a crash there leaks a day of storage that
nothing would reclaim. That is what `retention_orphans_dropped{type}` counts, and why it
is a separate series from `retention_partitions_dropped{type}`: folded together, a dead
run would read as retention working.

**The retention cutoff must be computed in exactly one place.**
The sweep drops a partition wholly older than the window; the guard skips a record
older than the window. Let those two drift by a day and a record is admitted into a
partition that has just been dropped — the `INSERT` fails, the record dead-letters, and
every redrive re-derives the identical verdict forever in a queue that never expires.
`RetentionWindow` is the one definition and both read it (DL-133). The boundary belongs
to the **retained** side on both sides of the comparison, deliberately: over-retaining
by a day costs one partition, under-retaining deletes records still inside the window
and nothing reports it.

**Modules declaring the same topology can break startup.**
If two modules declare the same exchange or queue with different arguments,
RabbitMQ rejects the mismatched declaration with `RESOURCE_LOCKED`. Declarations
must come from shared constants in `stats-common` and be identical and idempotent.

**One stat type can speak several dialects at once, and nothing on the record says
which.**
Three consecutive production `lorastats` records used three incompatible payload
shapes — bare names with integers, bare names with strings (`"176"`), and
`lora_`-prefixed names. **All three carried `version: 1`.** Anything that branches
on `version` to pick a parser is wrong — and this got *more* dangerous, not less:
since **DL-105** we accept all three and select between them **structurally**, and
since **T-063** (8 Sep 2026) `LorastatsDialects` is real code with `version` sitting
right next to it looking exactly like the discriminator it is not. The build stops
that specific mistake — `VersionIsNotADiscriminatorTest` forbids the file from naming
the identifier at all — and it cannot stop the reasoning behind it.

**Dialect C is not "everything prefixed", and the description is what misleads you.**
The production record carries fifteen `lora_`-prefixed counters alongside a **bare**
`fsk_received` and a **bare** `timespan`, and no `rx_type_arol` at all. A schema branch
or a detector written from the prose rejects the only traffic it exists to accept.
Read `docs/schemas/fixtures/valid/lorastats-dialect-prefixed.json` first.

**Normalise before you map, not alongside.** `data_extra` is the residue of the
*normalised* payload. Taken from the wire shape instead, every dialect-C record drops
fifteen `lora_*` members into it, leaves the typed columns NULL, and increments
`records_unmapped_data_field` — a counter whose whole job is to say "Heimdal added a
field" — on ordinary traffic every ten minutes. The write succeeds and the JSON is
valid, so the only symptom is a column that is always null.

Two halves of the original decision still hold. The enumeration is **closed at
three** — an unknown fourth dialect dead-letters, because a union allowed to grow
silently is a contract nobody understands. And **you still do not widen a schema to
make a DLQ spike go away**: DL-105 widened it because ZC explained the dialects, not
because the DLQ was full.

**The SRS field names are not the wire field names.**
Every envelope name in SRS v1.0 — `gateway`, `ts.processor` — is wrong, and the
whole document set agreed with it consistently for months. Consistency across
documents is not evidence of correctness when they all derive from one source. If
you are adding a field, check it against a production record, not against another
document.

**The same gateway in two letter cases is two gateways.**
`clsbox` is 12 hex characters and hex has two spellings. Store both and the key
`(ts_device, uid)` still suppresses duplicates — but `WHERE clsbox = :clsbox` finds
half a device's history, and a case-variant record looks like a new gateway. Since
**DL-115** the Writer uppercases before the `INSERT`, `CHECK (clsbox ~
'^[0-9A-F]{12}$')` holds the invariant at the table, and **every new stat-type table
must carry that CHECK**. Two things not to "tidy": the envelope schema still accepts
either case on purpose — a lowercase record is never 400'd — and the API filter is
`clsbox = upper(:clsbox)`, because `upper(clsbox) = …` silently stops the
`(tenant, clsbox, ts_device DESC, uid DESC)` index being used.

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

Most of the v1.0 blockers are now closed, and **nine more closed on 4 September 2026**
(DL-104…DL-112). What remains is below. Do not invent an
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
| Q13a | ~~Assume synchronised 10-minute boundaries~~ — **amended 4 Sep 2026 (Q13b, DL-104): gateways JITTER** | Design target of **13,333/s** is now **configured headroom**, not the expected case; expected steady state is the average, ~1,333/s. Writer prefetch starts at 500; Writer is sized for drain (~2,000/s per type). **Alert on oldest message age, never on depth — unchanged.** But the reading inverts: a cyclic depth spike used to be the buffer working, and under jitter it is **evidence the answer is wrong**. Hardware is not sized down until the `ts.server mod 600` histogram over the first 24 h of ingest confirms it (T-050) |
| Q13a (replay) | **Heimdal AGREES the 2× nominal cap (2,667/s)** — Q13c answered 4 Sep 2026, DL-109 | A 6-hour outage is 28.8M messages and about 3 hours of replay at that cap, holding total ingest at 4,000/s. **Not one defence changes: the agreement is a commitment, not a control.** 503 is the backpressure channel and is the mechanism working, not an incident. Replayed records are duplicates by definition and `ON CONFLICT DO NOTHING` absorbs them. T-049 unblocked |
| Q11a | **Built**, then **rebuilt on production 29 Aug 2026 (ADR-007)**, and reshaped again by T-063/T-064 on 8 Sep 2026. **79** fixture files across `valid/`, `invalid/`, `edge/`, `malformed/` — 77 individual fixtures plus the two kept legacy bundles | `docs/schemas/fixtures/`, catalogued in `MANIFEST.md`. `docs/schemas/validate-fixtures.py` asserts **91** and exits non-zero for CI, over three `$expect` markers: `input-reject`, `writer-dlq` and `writer-range-reject`. The 18 Aug set found three real schema gaps; the 29 Aug production sample found that **four of the five payload schemas and every envelope field name were wrong**, and inverted four fixture assertions outright |

### Still needs ZC / Hasan

Three items — two carried since August and one added on 12 September 2026 from the
Datahub API guide (DL-139). Everything else on this list was answered on 4 September
2026 — see *Decided 4 September 2026* below.

| Ref | Question | Blocks |
|---|---|---|
| Q8a-2 | Heimdal inbound auth, when ZC is ready: mTLS or OAuth2 client credentials; issuer, audience, client identity, rotation. Our position is OAuth2 client credentials, to be sent as *"unless you object by \<date\>, we implement OAuth2"* | Nothing today — deferred by decision. **Must close before any shared deployment**; until then anything that can reach the Input port can write |
| **Q8a-3** | **Is the stats push a Heimdal ForwardRule?** The Datahub API guide (§9) defines one as `{url, type, id, maxRetry}` with *"credentials in the URL"* over `HTTP\|AMQP\|MQTT\|…`. If so: the inbound auth is HTTP **basic**, which `stats.input.auth.mode=none\|mtls\|oauth2` cannot express, and Heimdal's retry on a non-2xx is **bounded by `maxRetry`** — a sustained 503 burns a budget we cannot see. Ask together with Q8a-2 (DL-139) | Nothing today. Changes the *shape* of the Q8a-2 answer and the reading of rule 8; the guide documents sensor forwarding and does not say gateway stats use the same path |
| Q8b-2 | Confirmation of the proposed Keycloak realm, client, issuer, audience, role names and tenant claim | Nothing today — the proposal is live and confirmation is a config change |

**Residuals carried inside a closed decision.** Not open questions — none blocks
anything, and each is recorded so it is not rediscovered as a surprise. Full table:
`claude/DECISIONS.md` §12.

| From | Residual |
|---|---|
| DL-105 (Q15a) | *Are the other `lorastats` dialects being retired?* Only decides whether a parser can ever be deleted; `records_lorastats_dialect{dialect}` is what will show one going to zero |
| DL-108 (Q15d) | The vocabulary of non-OK `status` values — the counter is value-agnostic and will enumerate it. **One more known-good value since DL-139:** measurement documents carry `["ok"]` where stats records carry `["conversion ok"]`; set T-050's threshold after the first 24 h, not at zero |
| DL-110 (Q4b) | IPv6 is *"for now, no"*. **A non-zero count on the metered IPv4 rejection code reopens it** |
| DL-104 (Q13b) | Jitter is a statement, not a measurement. Hardware is not sized down until the `ts.server mod 600` histogram confirms it (T-050) |
| DL-109 (Q13c) | The 2× replay cap is agreed and **unenforceable by us** |

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
| D5b | **Engineering agrees the late-arrival policy** (skip, meter `records_expired_on_arrival`, ACK) and adds an alert when the counter is non-zero. **SIGNED 4 Sep 2026 — DL-112** | ADR-003; **T-026 built 10 Sep 2026 (DL-133); the alert is still T-050** |
| **ADR-007** | **The production wire format is the contract.** `clsbox` not `gateway`; `ts.server` not `ts.processor`; no rename layer anywhere. `ts.converter` accepted, preserved, metered — ADR-004's rejection clause withdrawn. Two validation gates: envelope at Input (400), payload at the Writer (dead-letter). ~~One `lorastats` dialect encoded, the rest dead-lettered~~ — **§4 reversed 4 Sep 2026 by DL-105; all three are accepted (see *Decided 4 September 2026*)**. `netstats` interfaces require only `tx`/`rx`. `ltestats` numerics are strings and the field is `access_Technology` | ADR-007; amends ADR-002 §12, ADR-003, ADR-004 |

**The redrive trap to carry into the runbook:** a message that sat in the DLQ long enough
becomes `expired_on_arrival` the moment it is redriven, because its `ts.device` has aged
past the retention window. A redrive after ~90 days is a no-op that increments a counter,
not a recovery.

### Decided 4 September 2026 — do NOT reopen

Nine rows closed off the open-questions sheet: seven answers from ZC / Hasan and the
two signatures that were owed. **Approver:** Naman Vashishtha. Full reasoning:
`claude/DECISIONS.md` DL-104…DL-112.

| Ref | Decision | Lands in |
|---|---|---|
| **Q15a** | **All three known `lorastats` dialects are accepted and normalised to one typed row** — bare/integer, bare/string, `lora_`-prefixed/integer. Four rules: detection is **structural, never `version`**; one stored shape and one API shape, with the dialect as the metric label `records_lorastats_dialect{dialect}` rather than a column; string coercion is **range-checked against the signed `BIGINT` maximum before conversion** and dead-letters on overflow or a bad parse, never zeroing or truncating; and the enumeration is **closed at three** — an unknown fourth dialect still dead-letters. **Supersedes DL-089**, reverses ADR-007 §4, and stops ~15M msg/day accumulating in a DLQ that may never expire. **Built 8 Sep 2026 — DL-127** | DL-105; ADR-007 Amendment §A1; **T-063 (done)** |
| Q15b | **`ts.device` is genuinely device-assigned** and sent as soon as the device records. Retention on `ts.device` is retention on measurement time | DL-106; STA-23B |
| Q15c | **`_id` never appears on the wire** — a Mongo export artifact of Heimdal's own store. No `_id` handling is built; D14 stays the safety net. **Not permission to reintroduce Mongo here** | DL-107 |
| Q15d | **`status` can report a failed conversion.** Store the record, meter `records_upstream_status_not_ok{type}` on anything but exactly `["conversion ok"]`, log at `WARN` — **never dead-letter**: the record is valid and a redrive would repeat forever. The comparison is value-agnostic on purpose. **Built 8 Sep 2026 — DL-128, which also settles what DL-108 left open: an *absent* `status` is not counted** | DL-108; **T-064 (done)** |
| Q13b | **Gateways jitter across the 10-minute cycle.** Expected steady state ~1,333/s; DL-082's 13,333/s becomes **configured headroom**. Hardware is not cut until the `ts.server mod 600` histogram over the first 24 h of ingest confirms it. **One reading inverts:** a cyclic queue-depth spike used to be the buffer working — under jitter it is evidence this answer is wrong | DL-104; amends DL-082; T-050 |
| Q13c | **Heimdal agrees the 2× replay cap (2,667/s).** T-049 unblocked. **Not one defence changes** — an agreement is a commitment, not a control | DL-109; T-049, T-052 |
| Q4b | **No IPv6 "for now."** `netstats` stays IPv4-only and strict, with a **distinct, metered per-field rejection code** whose non-zero count is the reopening trigger — not a calendar review | DL-110 |
| **D5a** | **SIGNED.** RPO ≤ 5 min via continuous WAL archiving; RTO 1 h per partition, 24 h full cluster. Obliges archiver-lag alerting and a **timed** T-051 | DL-111 ratifies DL-084; **T-051 unblocked** |
| **D5b** | **SIGNED.** Late arrival: skip, meter `records_expired_on_arrival`, ACK, **alert when non-zero**. Signed now rather than on 29 Aug because DL-106 makes the path real rather than hypothetical | DL-112 ratifies DL-083; **T-026 built 10 Sep 2026 (DL-133)** — the alert half is T-050 |

### Built and decided 5–12 September 2026 — the state the next task starts from

Nothing here reopens a question. It is what exists in the repository now, so that a
plan is not written against a repository that no longer exists. Full reasoning:
`claude/DECISIONS.md` DL-113…DL-139.

| Ref | What is now true | Where |
|---|---|---|
| DL-118 | **2xx follows the confirm and nothing else.** Nack, timeout, interrupt and connection failure are all 503. An acked-but-**returned** message is a failed acceptance: 503 + `records_unroutable{type}`. Input declares the exchange and no queues | T-014, T-015 |
| DL-119 | **Byte-identity is proved by a fixture built to fail** — keys out of order, mixed indentation, a unicode escape, the `BIGINT` maximum, no trailing newline. `RabbitTemplate.send` with a hand-built message, never `convertAndSend` | T-016 |
| DL-120 | **The auth seam is a servlet filter, not Spring Security — yet.** No default in code; `none` warns on every startup; `mtls`/`oauth2` fail while the context builds; `prod` refuses `none` | T-017 |
| DL-121 | **Request limits are engineering defaults in properties.** 256 KB → 413 `payload_too_large`; 4,000 req/s **per instance** → 429 with `Retry-After`. D-03 can still return any number; an unbounded ingest endpoint is wrong at all of them | T-018 |
| DL-122 | **The failure arm is built.** Three tiers, a per-type DLQ that expires nothing, and the onward copy **confirmed before** the original is ACKed. It was idle until T-057; since 8 Sep 2026 it has producers | T-024 |
| DL-123 | **One topology, by construction.** `AmqpTopology` owns durability and auto-delete as well as names and arguments; both broker-connected modules build from it; one assertion checks both | T-032 |
| DL-115 | **`clsbox` is stored in one canonical case** — Writer uppercases, `CHECK (clsbox ~ '^[0-9A-F]{12}$')` backs it at the table, schema still accepts either case, API filters `clsbox = upper(:clsbox)`. **Every new stat-type table must carry the CHECK** | T-011, T-022, T-040 |
| DL-113, DL-124 | **Two build-failing guards, both written before the code they constrain.** No main source may branch on `version`; no throwable may reach a `stats-writer` log line — `SafeFailure` gives type names and a validated `SQLState`, never a message. **T-063 landed with no new whitelist entry on either**, which was the acceptance criterion | T-063, T-066 |
| DL-117 | **`redocly lint` 2.51.2 gates the API contract in CI**, before the Gradle build | T-038…T-046 |
| DL-116 | **The non-Markdown artifacts caught up with DL-105** — properties, `.env.example`, schema catalogue, fixture manifest, `openapi.yaml`. They stated the build state of the day, which is why all of them changed again on 8 Sep when T-063 made all three dialects valid | T-063 |
| DL-126 | **The payload gate is live and NOT configurable.** Type-agnostic `PayloadGate`, one parse, `ValidatedRecord` into the writer, `records_payload_rejected{type,reason}` with `reason` closed at `payload_schema`/`type_mismatch`/`unmappable`, and a **constant** failure message so nothing from the record can ever reach a log. **`records_write_failed` means the INSERT was attempted and PostgreSQL refused it** — a payload nobody can map is counted as `unmappable`, because it never reaches the database and sending an operator there is a wasted incident. `intended-scope` is deleted rather than promoted | T-057 |
| DL-127 | **Three dialects, one row.** Closed `oneOf` of three, structural detection, range-checked coercion *before* conversion, `data_extra` taken from the **normalised** residue. The dialect is the label `records_lorastats_dialect{dialect}`, never a column. A fourth spelling still dead-letters | T-063, T-060, T-062 |
| DL-128 | **A non-OK upstream `status` is metered at the gate and stored.** Never dead-lettered — a redrive repeats the verdict forever. **Absence is not counted**; the comparison is value-agnostic against the one known-good value | T-064, T-050 |
| DL-132 | **Retention is detach-and-drop, and every run repairs the last one.** One daily pass per type — pre-create **then** retain — behind the same advisory lock, staggered by an offset derived from the stat type. Each run starts with `DETACH … FINALIZE`, because an interrupted detach wedges every later one while ingestion carries on looking healthy; leftovers from a crash between detach and drop are reclaimed and counted separately. Nothing is dropped whose **name** this module did not produce, whose **catalogue bound** disagrees with that name, or whose day is not wholly outside the window | T-025, T-031, T-051, T-054, T-055 |
| DL-133 | **A late record is skipped, metered and ACKed — never dead-lettered.** `RetentionGuard` **returns a decision where `PayloadGate` throws**, because this path must end in a broker ACK. It reads the **same cutoff** as the sweep (`RetentionWindow`), so a record cannot be admitted into a partition that has just been dropped. Per-type windows are `stats.retention.days-by-type.<wire-name>`, validated at startup; `default-days` has no default in code | T-026, T-031, T-050, T-052 |
| DL-134 | **Correlation rides on standard AMQP properties, and every log statement owns an `event`.** Input mints `traceId` → `X-Trace-Id` + AMQP `correlationId`; `uid` → `messageId`; the Writer restores both before parsing and preserves them on every retry and DLQ copy. `uid`/`statType`/`tenant`/`clsbox`/`errorCode` live in a scoped MDC with the bounded literal `unknown` before validation. `StructuredLoggingContractTest` fails the build without an `event` key; the throwable guard now walks the fluent API too | T-005 |
| DL-135 | **Database capability is proved by PostgreSQL.** `stats_ro` has `CONNECT`, `USAGE`, `SELECT` — and `DatabaseRolesIntegrationTest` proves `INSERT`/`UPDATE`/`DELETE`/`TRUNCATE`/`CREATE`/sequence access each fail with SQLSTATE `42501` on a table created *after* bootstrap. Compose blanks the database variables in `stats-input` and hides each module's identity from the others. There is no `stats_input` database identity | T-009 |
| DL-136, DL-137 | **The Writer is observable, and a scrape performs no I/O.** `records_consumed` before decoding, `records_processing_duration` on every exit, `records_persisted` only after a committed insert, `records_postgres_errors` by exception *type*. `queue_depth`, `dlq_depth`, `consumer_availability` are polled on `stats.metrics.poll-interval` and cached; a failed poll writes **NaN** and drops `broker_metrics_collection_success` — a queue the broker cannot report on must never read as empty. Aggregate replicas with `max`, never `sum`. The branch's second retention cutoff and polled lag gauge were dropped on merge. Contract: `docs/contracts/writer-metrics-v1.md` | T-027 |
| DL-138 | **A manual redrive is one attempt, never fresh tiers.** `DlqRedriveCommand` (`./gradlew :stats-writer:redriveDlq`): per type, ≤ 2,667/s, `--change-id` and `--root-cause-resolved=true` mandatory, refuses a record past the retention window, publishes the original bytes with `x-stats-manual-redrive=true` and ACKs the DLQ original **only after the copy is confirmed**. `FailedDeliveryRouter` sends a repeat failure straight back to the DLQ. Runbook: `docs/runbooks/dlq-redrive.md` | T-052 |
| DL-139 | **The Datahub API guide is reconciled.** `maintenant` pinned down — it moves with `tenant` and equals it, so only a deliberate fixture can ever catch a wrong filter; Q8a-3 (ForwardRule / `maxRetry` / basic auth) and D16 (tenant-move history) raised, not answered — **D16 since ratified, DL-142**; `["ok"]` added to the DL-108 residual; `clsbox = FFFFFFFFFFFF` means *synthetic / no gateway*. The platform's own `POST /datastore/aggregate/pageable` on `{clsbox, type, ts.device}` is a **parity oracle** for the API tests | — |

> **What to do next lives in `Stats_App_Tracker.xlsx` → the `Next Up` sheet**, ranked
> across both owners with the gate on each row. T-063, T-057 and T-064 were done on
> 8 Sep 2026; **T-025 and T-026 on 10 Sep 2026**, which also covers T-031's boundary
> assertions on both sides; **T-005, T-009 and T-027 merged on 10 Sep and T-052 on
> 11 Sep 2026** — Phase 1 has no PARTIAL row left. In one line: **T-060 and T-050
> first** — every counter T-050 alerts on and both queues T-060 watches now exist, the
> redrive exists, and **nobody is watching any of it**. Then **T-029/T-030/T-033**, which
> is what lets the fan-out start at **`sysstats`** — not `ltestats`, which is blocked on
> D-12 P3. T-051, T-054 and T-055 are unblocked — they measure what T-025 built.

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
| **D16** | **Tenant-move history.** When a gateway is moved to another tenant on the Datahub, new records arrive with the new name in both `tenant` and `maintenant` — but the rows this system wrote *before* the move still carry the old `tenant`, and ADR-006 puts `tenant` in every query, so the new tenant cannot see them (DL-139) | **DECIDED 17 Sep 2026 — DL-142, ADR-006 Amendment.** That is the intended reading. A row's `tenant` is the tenant at measurement time, exactly as Heimdal stamped it — ADR-007 forbids rewriting the record, and re-labelling history on a move would need the Writer to read the inventory service, a dependency this project does not have, and would hand the *new* tenant data measured for the old one. No code change; a new tenant asking for pre-move rows gets 404 (STA-35B). The support answer is *by design* |
| — | Gradle DSL, package layout, CI shape | **Ours — Q1a closed, there is no reference service to match.** Kotlin DSL, version catalog, `com.zenner.datahub.stats.<module>`, `./gradlew build` plus Testcontainers on every push (ADR-001) |

**Decided in ADR-003:** PostgreSQL 17; daily `RANGE (ts_device)` partitions with
detach-and-drop retention; typed columns for the four stable types and a
`jsonb` hybrid for `netstats`; `stats-writer` owns retention behind an advisory
lock; Flyway; duplicate suppression metered rather than
silent, with `tenant` out of the key until the metric says otherwise; a
`(tenant, uid)` secondary index for the `uid`-only lookup.

### Needs product / operations

**Nothing.** D5a and D5b were the two rows here, and both were **signed by Naman
Vashishtha on 4 September 2026** — see *Decided 4 September 2026*. **T-051 and T-026
are unblocked.**

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
| ADR-003 | PostgreSQL retention, partitioning, indexes | **APPROVED** (24 Aug 2026) | PG17, daily `RANGE (ts_device)` partitions, typed columns, Flyway; key order `(ts_device, uid)` per G2, with STA-21B/STA-25A amended to match and duplicate suppression metered. Secondary index `(tenant, uid)` carries the `uid`-only lookup, which cannot prune partitions. Restore is per-partition (D5). **Amended 29 Aug 2026:** D1 makes partition pre-creation a scheduled task in `stats-writer` behind the D3 advisory lock, with `pg_partman` never a dependency; **D9 closes Q14a** — a `ts.device` beyond the configured skew tolerance is rejected at Input. **Amended 4 Sep 2026:** both rows that were open inside this approved ADR are now **signed** — D5a at RPO ≤ 5 min / RTO 1 h per partition and 24 h full cluster (DL-111), and D5b's late-arrival policy (DL-112). T-051 and T-026 are unblocked, and Q15b (DL-106) confirms the `ts.device` basis those decisions rest on. **Amended 10 Sep 2026 (DL-132, DL-133):** D1's retention half and D5b's late-arrival policy are **BUILT** (T-025, T-026), and two mechanisms this ADR did not name are added — every run finalises an abandoned `DETACH … CONCURRENTLY` (an interrupted one wedges all later detaches while ingestion looks healthy), and the sweep reclaims tables detached but never dropped. The guard and the sweep read **one** cutoff. |
| ADR-004 | OMS timestamp normalisation | **ACCEPTED — amended 29 Aug 2026 by ADR-007 §2** | No normalisation; publication is byte-identical to the received record, which is what makes ADR-003's per-partition duplicate suppression work. **Clause 3 is withdrawn:** a record carrying `ts.converter` is **valid** — production sends it on 100% of records. Accepted, preserved, never read, metered. Cite the ARD's different ADR-004 as `ARD-ADR-04`. |
| ADR-005 | Delivery semantics and idempotency | **ACCEPTED with implementation defaults** | Writer batching amended by G3. Retry/DLQ handoff must be publish-confirmed *before* ACKing the original — ACK-then-publish opens a silent loss window. Duplicate outcome is `ON CONFLICT (ts_device, uid) DO NOTHING`, metered. Replay spikes the duplicate counter by design; do not alert on it. Q13c (2× replay cap) is **agreed by Heimdal since 4 Sep 2026 (DL-109) and still not enforceable by us** — 503 backpressure, bounded prefetch and T-018's rate limit stay exactly as they are. |
| ADR-007 | The production wire format is the contract | **ACCEPTED** (29 Aug 2026) — **§4 amended 4 Sep 2026, built 8 Sep 2026** | `clsbox`/`ts.server` end to end with no rename layer; `ts.converter` accepted and metered (amends ADR-004); two validation gates with different consequences — **and since T-057 both gates exist in code**, so "invalid" genuinely no longer implies "400" (DL-126). **§4 is reversed by the *Amendment — 4 September 2026*: all three known `lorastats` dialects are accepted and normalised to one row (DL-105 supersedes DL-089), under a closed enumeration with structural detection and range-checked coercion — implemented by T-063 (DL-127).** All four of its open questions (Q15a–Q15d) were answered on 4 Sep 2026 — DL-105…DL-108 — leaving one residual: whether the other dialects are being retired. Also amends ADR-003 (column names); its DLQ-volume warning to ADR-002 §12 is resolved. |
| ADR-006 | Tenant isolation and API security | **ACCEPTED** | The isolation decisions are settled and buildable; only ZC's confirmation of the Keycloak values (Q8b-2) is outstanding, and it is a config change. Proposed realm is live in dev — read role is `stats.readOnly`, **not** the bare `readOnly` placeholder. Input auth is deferred by decision (Q8a): `stats.input.auth.mode` has no default in code and `prod` must refuse `none`. **Amended 17 Sep 2026 (D16, DL-142):** a gateway moved between tenants keeps its pre-move rows under the old `tenant` — ratified as correct; `tenant` is the value at measurement time and nothing re-labels history. No residual. |

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
| `docs/contracts/writer-metrics-v1.md` | **Every Writer metric, its labels and its measurement boundary** — what `records_persisted` does and does not count, why a failed broker poll is NaN, how replicas aggregate (DL-136, DL-137) |
| `docs/runbooks/dlq-redrive.md` | **The DLQ redrive procedure** — access, one-record pilot, audit line, stop conditions, and the 90-day trap made checkable against `records_expired_on_arrival` (DL-138) |
| `docs/contracts/openapi.yaml` | **The API contract.** OpenAPI 3.1, GET-only, `/v1/stats/{type}`. Draft pending ZC confirmation; gates Phase 4 |
| `docs/contracts/openapi-v1-design.md` | Design input for the above. Converted 21 Aug 2026 — the YAML wins where they differ |
| `docs/schemas/` | JSON Schema catalogue, per-type schemas, the 75-file fixture set (`fixtures/MANIFEST.md`), and `validate-fixtures.py` for CI |
| `docs/decisions/2B-ANSWERS-18AUG2026.md` | The seven §2b answers of 18 Aug 2026 and every engineering decision derived from them |
| `docs/decisions/DATA-SENSITIVITY-INVENTORY.md` | **Every field in all six schemas, the six sinks it can reach, and what class of identifier it is.** The input document for **D-12** — engineering's, not a classification. Read it before touching logging, masking, or `ltestats`/`netstats` persistence |
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
