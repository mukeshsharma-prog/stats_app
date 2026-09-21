# Stats App — Execution Flow

How a record actually travels, step by step, from the Heimdal Core to an
API response — and what happens at each point where it can fail.

> **Read `claude/DECISIONS.md` alongside this.** This file says *what
> happens*; that one says *why it happens that way*. Steps here cite
> `DL-nnn` entries rather than repeating the reasoning.

---

## 0. Status legend — what is built and what is specified

**Much of this document still describes a design that is not yet code**, but
less of it than when the file was written. The distinction is marked on every
flow, because a specified flow and a running one are not the same claim.

| Marker | Meaning |
|---|---|
| **`BUILT`** | Implemented and under test in this repository today |
| **`PART BUILT`** | Some steps of the flow are real; the rest is still specified. The step list under each such heading says which is which |
| **`SPECIFIED`** | Decided and contracted; not yet implemented |
| **`BLOCKED`** | Cannot be built — an open question owns it |

### What exists today (21 September 2026)

`stats-common` is complete, `stats-writer` and `stats-input` are substantially
built — three stat types end to end, watched, and run end to end on the Compose stack —
and `stats-api` is still a stub with a contract that now carries three operations.

| Component | Status |
|---|---|
| `StatType`, `StatsEnvelope`, `Timestamps` + epoch converters | **`BUILT`** (T-001) |
| `AmqpTopology` — names and declaration argument maps | **`BUILT`** (T-002) |
| `ErrorCode`, `FieldError`, `ProblemDetails` (RFC 9457) | **`BUILT`** (T-003) |
| `StatsSchemaValidator`, `ValidationResult` (Draft 2020-12) | **`BUILT`** (T-004). Catalogue checked against 1,200 live records on 18 Sep 2026 — `ltestats` became a closed `oneOf` of two shapes and the envelope accepts `expiresOn` in both spellings (DL-146) |
| `stats-input` — envelope gate, clock-skew policy, ingest metrics, controller | **`BUILT`** (T-013, T-059) |
| `stats-input` — publish with confirms, mandatory/return, byte-identical publication | **`BUILT`** (T-014, T-015, T-016; DL-118, DL-119). `CONFIRMED` follows a broker ack with no return and nothing else; an unroutable message is a failed acceptance — 503 and `records_unroutable{type}` |
| `stats-input` — auth seam and request limits | **`BUILT`** (T-017, T-018; DL-120, DL-121). The *mechanism* behind the auth seam is still Q8a-2; `mode=none` is unauthenticated by decision and refused under `prod` |
| `stats-writer` — `lorastats` table, indexes, partition pre-creation | **`BUILT`** (T-010 … T-012) |
| `stats-writer` — per-type consumer, manual ACK, batch-shaped idempotent insert, range check, `passthrough`, `data_extra` | **`BUILT`** (T-019 … T-023, T-058; DL-095) |
| `stats-writer` — `clsbox` normalised to one canonical case, with the table's own `CHECK` | **`BUILT`** (T-011, T-022; DL-115, V5). The schema still accepts either case: a lowercase record is normalised, never rejected. **Every new stat-type table must carry the `CHECK`** |
| `stats-writer` — `SafeFailure`: no throwable, and therefore no payload value, in a log line | **`BUILT`** (T-066; DL-124). Type chain plus a validated `SQLState`, never a message. `WriterLogsCarryNoThrowableTest` fails the build |
| Architecture guards — nothing branches on `version`; no stat type named in main source | **`BUILT`** (DL-113, trap 15). Written *before* T-063 created the dialect-selection code that now sits beside `version` — and T-063 landed with **no new whitelist entry**, which was the acceptance criterion |
| API contract linted in CI (`redocly lint` 2.51.2) | **`BUILT`** (DL-117) |
| OpenAPI per-type data schemas checked against `docs/schemas` | **`BUILT`** (T-047, DL-140). Five components, local references resolved, with explicit Lorastats and Netstats projections; mutation tests prove the gate fails |
| `stats-writer` — three retry tiers, per-type DLQ, confirmed hand-off before ACK | **`BUILT`** (T-024; DL-122). Delays and the ceiling are properties; a permanent failure skips the tiers |
| `stats-writer` — per-type payload gate, straight to the DLQ with no retry | **`BUILT`** (T-057; DL-126). Type-agnostic `PayloadGate`, one parse, `ValidatedRecord` into the writer, no property that can switch it off. Binding the row and writing it are separate stages with separate counters: a payload nobody can map never reaches PostgreSQL, so it is never counted as a write failure |
| `stats-writer` — all three `lorastats` dialects normalised to one row | **`BUILT`** (T-063; DL-127). Structural detection, range-checked coercion before conversion, closed at three. The dialect is the metric label `records_lorastats_dialect{dialect}`, never a column |
| `stats-writer` — a non-OK upstream `status` metered and stored | **`BUILT`** (T-064; DL-128). `records_upstream_status_not_ok{type}`, `WARN`, never a dead-letter. An *absent* status is not counted |
| `stats-writer` — retention: `DETACH PARTITION … CONCURRENTLY` then `DROP TABLE` | **`BUILT`** (T-025; DL-132). Never row deletion. One daily maintenance pass — pre-create, *then* retain — per type, staggered, behind the same advisory lock. **Every run finalises a detach an earlier run left pending**, because an interrupted detach wedges every later one while ingestion carries on looking healthy |
| `stats-writer` — late arrival: skip, meter, ACK | **`BUILT`** (T-026; DL-133). `RetentionGuard` reads the **same cutoff** as the sweep, so a record can never be admitted into a partition retention has just dropped. `records_expired_on_arrival{type}` + `WARN`. **Never dead-lettered.** The *alert* on that counter is `StatsAppLateArrival` (T-050, DL-147) |
| `stats-writer`, `stats-input` — structured logging with correlation | **`BUILT`** (T-005; DL-134). `traceId` minted at Input → `X-Trace-Id` + AMQP `correlationId`; `uid` → `messageId`; Writer restores both *before* parsing. Every application log statement carries an `event` key or the build fails |
| Database roles proved, not declared | **`BUILT`** (T-009; DL-135). `stats_ro` cannot `INSERT`/`UPDATE`/`DELETE`/`TRUNCATE`/`CREATE` — SQLSTATE `42501`, asserted against PostgreSQL 17 on a table created *after* bootstrap. Compose blanks the database variables in `stats-input` |
| `stats-writer` — operational metrics | **`BUILT`** (T-027; DL-136, DL-137). `records_consumed`, `records_persisted`, `records_processing_duration`, `records_postgres_errors`; `queue_depth`, `dlq_depth`, `consumer_availability` polled and cached, NaN on a failed poll. Contract: `docs/contracts/writer-metrics-v1.md` |
| DLQ redrive — guarded, per type, one attempt | **`BUILT`** (T-052; DL-138). `DlqRedriveCommand` confirms the copy before ACKing the original; `x-stats-manual-redrive` sends a repeat failure straight back to the DLQ. Runbook `docs/runbooks/dlq-redrive.md` |
| Cross-module topology declaration agreement | **`BUILT`** (T-032; DL-123). One set of definitions in `stats-common`, one assertion in `stats-testsupport` called by both modules, and `stats-api` asserts it has no AMQP client at all |
| Liveness/readiness groups per module | **`BUILT`** (T-006, DL-103) |
| The three Phase 2 drills — poison isolation by type, both crash windows, database outage and recovery | **`BUILT`** (T-029, T-030, T-033; merged 17 Sep 2026). Real broker and database; T-033 is what released the fan-out |
| Consumer shutdown finishes the delivery in hand and requeues the rest | **`BUILT`** (T-053; DL-143). An interrupt is lifecycle, never a retry or an ACK |
| DLQ alerting | **`BUILT`** (T-060; DL-144). `ops/prometheus/dlq-alerts.yml` — depth > 0, continuously non-empty 7 d / 12 d, gated on `broker_metrics_collection_success` |
| Main-queue watching — oldest-message age, no consumer, consumer stalled, late arrival, upstream status | **`BUILT`** (T-050; DL-147). Input stamps the AMQP `timestamp`; `queue_oldest_message_age_seconds{type}` is sampled at delivery and zeroed by the poll on an empty queue. The Q13b histogram `records_ts_server_cycle_offset_seconds{type}` is Input's |
| `sysstats` — the second stat type end to end | **`BUILT`** (T-035; DL-148). Own queue set, consumer, gate-then-guard processor, `V6__sysstats_table.sql` with the `CHECK`, `data_extra` and both ADR-003 indexes; `timespan` `"300"` range-checked before conversion. The shared beans a second type needs live in `WriterAmqpConfiguration` / `StatTypeDeployment`, not in the lorastats slice |
| `omsstats` — the third stat type end to end | **`BUILT`** (T-036; DL-153, 21 Sep 2026). A copy of the sysstats slice that moved nothing shared: own queue set, consumer, gate-then-guard processor, `V7__omsstats_table.sql` with the `CHECK`, `data_extra` and both ADR-003 indexes. `ts.converter` preserved in `passthrough`, no normalisation column (ADR-004); an integer wider than `BIGINT` is `unmappable` before conversion (`valid/omsstats-counter-overflow.json`). `demo.sh` step 1 posts it |
| One wire-number coercion for every writer | **`BUILT`** (T-062; DL-154, 21 Sep 2026). `WireNumbers` — integer or digit string, signed or not, range-checked against `BigInteger` before conversion, every refusal permanent and value-free; `WireNumbersIsTheOnlyCoercionTest` fails the build on a second copy. Fixed on the way: a lorastats integer overflow was retried through three tiers before dead-lettering |
| Advisory-lock contention between replicas — drilled; a sweep that never runs is visible | **`BUILT`** (T-054; DL-156, 21 Sep 2026). `AdvisoryLockContentionIntegrationTest` (two pools, twenty simultaneous rounds, pools clean afterwards) and `ops/drills/t054-advisory-lock-contention.sh` (two processes on the live stack, run and passed). Retention gauges are `NaN` until the first sweep; `retention_last_sweep_timestamp_seconds{type}` stamped on completion; `StatsAppRetentionSweepMissing` |
| `DETACH CONCURRENTLY` does not block the writer — measured | **`BUILT`** (T-055; DL-157, 21 Sep 2026). `DetachConcurrentlyLatencyIntegrationTest`: sweep of a 300k-row day, worst insert 1.9 ms; behind an open reader the sweep waits 1.56 s while 13,436 inserts continue (worst 13 ms); the plain-DETACH control stalls inserts 1,515 ms. `inhdetachpending` zero after every phase |
| Per-partition restore — drilled and timed | **`BUILT`** (T-051; DL-155, 21 Sep 2026). `docs/runbooks/partition-restore.md` (load-then-attach, four post-attach checks) and `PartitionRestoreDrillIntegrationTest`: 200k-row day restored and re-attached in **1.23 s** with a writer inserting throughout (RTO 1 h). WAL archiving on in Compose with `archive_timeout=300`; `postgres-alerts.yml` pages when the archiver fails or stalls (RPO ≤ 5 min) |
| Partition pre-creation covers the **whole** retention window | **`BUILT`** (DL-149). Every pass — startup and daily — ensures `[cutoffDay, today + pre-create-days]` from `RetentionWindow`. Before this, a valid in-window record older than first deployment had no partition and dead-lettered as *permanent* (window 15) |
| No Writer log line repeats a key the MDC scope owns | **`BUILT`** (DL-150). `WriterLogsDoNotShadowMdcKeysTest` fails the build; `EcsEncoderDropsDuplicateKeysTest` pins why. Seven `WARN` sites had been silently dropped by the ECS encoder since T-005 |
| End-to-end demo against the Compose stack | **`BUILT`** (T-069; `docker/demo.sh`, 20 Sep 2026). Ten steps, each naming the rule it proves; found DL-149 and DL-150 on its first run |
| `stats-input` — two doors, one handler | **`BUILT`** (DL-151). `POST /internal/stats` (Heimdal) and `POST /external/stats` bound to the same handler, same filter chain, one rate bucket; `source="internal"\|"external"` on every Input metric but `records_unroutable`, `ingestSource` on every log line. **Not a second auth mechanism; no tenant binding on the external door** — T-068, blocked on Q8a-2 |
| `stats-api` — device-scoped read paths `/{type}/{clsbox}/{from}[/{to}]` | **`SPECIFIED`** (DL-151; T-067). In `openapi.yaml`, linted; open-ended one-argument form; foreign `clsbox` is an empty 200; no window limit; the `clsbox` query parameter is gone |
| `stats-api` | `@SpringBootApplication` **stub only** — Phase 4, gated on D-09 |

So: the **shared vocabulary** is real, and so is one type's whole path. Since
5 Sep 2026 a record travels from Heimdal to a queue, from the queue into a partition,
and — when the write fails — through three retry tiers to a dead-letter queue that
never expires anything. Since **8 Sep 2026** the failure arm is no longer idle: the
per-type payload gate (T-057) produces dead-letters, all three `lorastats` dialects are
accepted and normalised to one row (T-063), and a non-OK upstream `status` is metered
rather than passing unremarked (T-064). Since **10 Sep 2026** the record also *leaves*
again on a schedule: retention detaches and drops each partition past the window
(T-025), and a record that arrives already past it is skipped, metered and ACKed rather
than dead-lettered (T-026). Since **10–11 Sep 2026** the path is *observable* and
*operable*: every delivery is correlated end to end (T-005), the read-only role is
proved read-only by the database (T-009), the Writer exports its counters and the broker
depths (T-027), and a record can be brought back from the DLQ under a guarded procedure
(T-052). Since **17–19 Sep 2026** it is *watched*: T-060 alerts on the DLQ (DL-144) and
T-050 on the main queues' oldest-message age, late arrival and upstream status
(DL-147) — the age is sampled at delivery from a timestamp Input now stamps on every
publish. And since **19 Sep 2026 the fan-out has started**: `sysstats` is the second
type end to end (T-035, DL-148), consumed beside `lorastats` by its own listener into its
own table. Since **20 Sep 2026 the whole stack has been run end to end** (`docker/demo.sh`,
T-069) — which found that partitions only ever covered the days *ahead* (DL-149) and that
every Writer `WARN` with a `uid` on it was being dropped by the log encoder (DL-150); both
are fixed and guarded. Since **21 Sep 2026** the ingest endpoint is two doors on one handler
(DL-151) and the read contract carries the device-scoped paths (T-067, contract only). The
ranked order is `Stats_App_Tracker.xlsx` → `Next Up`. Phase 2 finished
`lorastats` first (DL-030 rationale; see `CODE_START_CHECKLIST.md` §B).

---

## 1. The whole path, at one altitude

```
   Heimdal Core
        │  HTTP POST  (one record, JSON)
        ▼
┌──────────────────────────────────────────────────────────┐
│ MODULE 1 — stats-input                         PART BUILT│
│   authenticate → parse → ENVELOPE-validate → skew-check  │
│   → publish (persistent, mandatory) → await confirm      │
│   200 only after confirm.  400 invalid.  503 broker.     │
│   NEVER touches PostgreSQL.                              │
└────────────────────────┬─────────────────────────────────┘
                         │ routing key = stat type
                         ▼
┌──────────────────────────────────────────────────────────┐
│ RabbitMQ  —  app_stats_input  (TopicExchange, durable)   │
│                                                          │
│   app_statsstore.<type>              main, quorum        │
│   app_statsstore.<type>.retry.1|2|3  TTL 30s/5m/30m      │
│   app_statsstore.<type>.dlq          NO TTL, EVER        │
│   app_stats_dlx                      the bounce exchange │
└────────────────────────┬─────────────────────────────────┘
                         │ consume, prefetch 500
                         ▼
┌──────────────────────────────────────────────────────────┐
│ MODULE 2 — stats-writer                        PART BUILT│
│   INSERT … ON CONFLICT (ts_device, uid) DO NOTHING       │
│   commit → THEN manual ACK                               │
│   Also owns: partition pre-creation, retention           │
│   The ONLY component with database write access.         │
└────────────────────────┬─────────────────────────────────┘
                         │
                         ▼
              ┌──────────────────────────┐
              │ PostgreSQL 17            │
              │ one table per stat type  │
              │ daily RANGE (ts_device)  │
              └────────────┬─────────────┘
                           │ read-only, tenant-scoped
                           ▼
┌──────────────────────────────────────────────────────────┐
│ MODULE 3 — stats-api                            SPECIFIED│
│   GET only.  Keycloak bearer → tenant claim →            │
│   predicate IN THE QUERY → keyset page                   │
│   No broker connection at all.                           │
└────────────────────────┬─────────────────────────────────┘
                         ▼
                  External consumer
```

**Three properties to keep in view while reading the rest:**

1. **Nothing transforms the record.** What Heimdal sent is what is
   published and what is stored (DL-050). Duplicate suppression depends on
   it.
2. **Every hop confirms before it releases.** Input confirms before 200;
   the Writer commits before ACK; a retry publish is confirmed before the
   original is ACKed. Each is a place where the obvious order loses data
   silently.
3. **The stat type is the axis of everything.** Routing key, queue,
   consumer, table, schema, metric label, endpoint parameter. Adding a type
   is additive across all of it, or the abstraction is wrong (STA-14A).

---

## 2. Ingest — `stats-input`   `PART BUILT`

> **`BUILT`:** parse, envelope validation, the clock-skew check, the error shape and
> the per-type counters (T-013, T-059); publish-and-confirm (T-014), the
> mandatory/return path (T-015), byte-identical publication (T-016), the
> authentication seam (T-017) and the request limits (T-018) — DL-118 … DL-121.
> A record that passes the gate is now published and answered with the configured
> success status **after** the broker confirms, and 503 on anything else.
>
> **`SPECIFIED`:** the authentication *mechanism*. `stats.input.auth.mode` has no
> default in code, `none` WARNs on every startup and the `prod` profile refuses to
> start with it — but while `none` is selected, anything that can reach the Input port
> can write. Q8a-2 is what closes that.

### 2.1 The happy path

```
POST /internal/stats      Heimdal's door           (both paths pending D-03)
POST /external/stats      every other caller — SAME handler, same gate, same
                          limits, same auth filter; differs only in the
                          `source` label on every metric and `ingestSource`
                          on every log line (DL-151). One shared rate bucket.
Content-Type: application/json
  │
  ├─ 0. LOGGING FILTER       traceId minted; ingestSource resolved from the
  │                          path — internal | external — once, for everything
  │                          behind it
  │
  ├─ 1. AUTH FILTER          mode from stats.input.auth.mode
  │                          none | mtls | oauth2 — no default in code
  │                          `none` ⇒ WARN on every startup
  │                          `prod` profile + `none` ⇒ REFUSES TO START
  │
  ├─ 2. READ BODY            keep the ORIGINAL BYTES
  │                          they are what gets published (DL-050)
  │
  ├─ 3. PARSE JSON           malformed ⇒ 400
  │
  ├─ 4. READ `type`          map through StatType.fromWireName
  │                          unknown ⇒ 400, and the literal is NEVER
  │                          echoed and NEVER used to build a resource
  │                          name (DL-053)
  │
  ├─ 5. VALIDATE ENVELOPE    StatsSchemaValidator.validateEnvelope()
  │                          THE ENVELOPE ONLY — not the payload.
  │                          invalid ⇒ 400 + RFC 9457 Problem Details,
  │                          field errors carry JSON Pointer + code,
  │                          never the offending value
  │                          The per-type payload schema is the WRITER's
  │                          gate (step W-1a below) and a payload failure
  │                          is a DEAD-LETTER, not a 400. Heimdal retries
  │                          only on a non-2xx, so a 400 here would make a
  │                          recoverable payload mismatch unrecoverable.
  │                          (ADR-007 §5, DL-088)
  │
  ├─ 6. CLOCK-SKEW CHECK     ts.device > ts.server + max-clock-skew ?
  │                          (default PT5M, externalised)
  │                          ⇒ 400, field code clock_skew_exceeded,
  │                            records_clock_skew_rejected{type}++
  │                          Cross-field, so it is CODE here, not a
  │                          schema rule (DL-052)
  │
  ├─ 7. PUBLISH              exchange  app_stats_input
  │                          routing key = type.wireName()
  │                          persistent + mandatory=true
  │
  ├─ 8. AWAIT CONFIRM        stats.amqp.confirm-timeout-ms = 5000
  │
  └─ 9. RESPOND              2xx ONLY NOW  (200 vs 202 pending D-03)
```

**Nothing is published until step 7.** An invalid record is rejected and
**nothing enters the broker** (rule 7, STA-03A).

### 2.2 Every failure exit

| Where | Condition | Response | Metric / log |
|---|---|---|---|
| 1 | Auth configured and fails | 401 / 403 | — |
| 1 | `stats.input.auth.mode` unset | **App does not start** | — |
| 3 | Malformed JSON | 400 | Problem Details |
| 4 | Unknown stat type | 400 | literal not echoed |
| 5 | Schema violation | 400 | field errors, pointers only |
| 5 | `ts.converter` present | 400 | invalid by construction (DL-050) |
| 6 | `ts.device` beyond skew tolerance | 400 `clock_skew_exceeded` | `records_clock_skew_rejected{type}` |
| 7 | Broker unavailable | **503** | Heimdal owns retry |
| 8 | Confirm times out | **503** | never 2xx on an unconfirmed publish |
| 8 | **Message returned** (unroutable) | **503** | `records_unroutable{type}`, ERROR + routing key |

### 2.3 The three hazards in this flow

**A 200 is a promise.** If Input returns 200 before the broker confirms,
a broker failure in that window loses the message **and** Heimdal will not
retry, because it saw success. Hence: confirm, then respond — never the
reverse (DL-033, STA-04A).

**A confirm is not a route.** A publisher confirm says the *broker*
accepted the message, not that any queue wanted it. With a missing
binding the broker ACKs and **drops the message on the floor** — 200
returned, no retry, record gone, everything reporting success. That is
what `mandatory=true` plus a `ReturnsCallback` exists to catch, and it is
why a returned message is a **failed** acceptance and not a warning
(DL-033).

**Input is stateless — there is no local buffer.** 503 is the
backpressure channel and it is the mechanism working, not an incident
(DL-082). Heimdal owns retry on any non-2xx (STA-06A).

---

## 3. Broker — routing, retry, and the DLQ   `BUILT` (T-024, T-032; the alerting on top of it is T-060)

### 3.1 Topology, and who declares it

Declared by **broker-connected modules only** — `stats-input` and
`stats-writer` — from the shared constants in `AmqpTopology`.
**`stats-api` holds no RabbitMQ connection at all** (DL-035).

```
app_stats_input                     TopicExchange, durable
  │
  ├── "lorastats" ──► app_statsstore.lorastats     quorum, durable
  ├── "ltestats"  ──► app_statsstore.ltestats
  ├── "sysstats"  ──► app_statsstore.sysstats
  ├── "netstats"  ──► app_statsstore.netstats
  └── "omsstats"  ──► app_statsstore.omsstats

app_stats_dlx                       the bounce exchange
  └── carries expired retry messages back to the main queue
```

Per type, four queues (all `x-queue-type: quorum`, DL-032):

| Queue | Arguments | Purpose |
|---|---|---|
| `app_statsstore.<type>` | quorum only | main |
| `app_statsstore.<type>.retry.1` | quorum, `x-message-ttl` 30 s, DLX + routing key | tier 1 |
| `app_statsstore.<type>.retry.2` | quorum, `x-message-ttl` 5 min, DLX + routing key | tier 2 |
| `app_statsstore.<type>.retry.3` | quorum, `x-message-ttl` 30 min, DLX + routing key | tier 3 |
| `app_statsstore.<type>.dlq` | **quorum, and nothing else** | terminal |

**Delays are externalised to `application.properties`, never
hard-coded** (DL-031, G4). `AmqpTopology` owns the names and the argument
maps; the *values* come from module configuration.

**Why the arguments live in `stats-common`:** two modules declaring the
same queue with different arguments is not a warning — RabbitMQ rejects the
mismatched declaration with `RESOURCE_LOCKED` and **the module fails to
start**. Quorum sharpens it: a queue's type is fixed at declaration, so
changing one means deleting and redeclaring it (DL-032, DL-014).

### 3.2 The retry mechanism — how a message moves

There is no retry scheduler. It is TTL plus dead-lettering:

```
main queue ──(processing failed)──► retry.1
                                      │  sits for 30 s (x-message-ttl)
                                      │  TTL expires ⇒ dead-letters to
                                      ▼  app_stats_dlx with routing key = type
                                   app_stats_dlx
                                      │
                                      ▼
                                   main queue          ← attempt 2
                                      │
                        (failed) ──► retry.2  →  5 min  →  main   ← attempt 3
                        (failed) ──► retry.3  → 30 min  →  main   ← attempt 4
                        (failed) ──► app_statsstore.<type>.dlq    TERMINAL
```

**Immediate requeue is never used for a persistent failure** — it is a hot
loop that burns broker and database capacity and achieves nothing
(DL-031).

### 3.3 The ACK ordering hazard

**Publish to the retry or DLQ destination, wait for the publisher confirm
on *that* publish, and only then ACK the original.**

The tempting order — ACK the failed message, then publish it onward —
opens a window where the original is gone and the copy does not exist yet.
A crash inside that window loses the record permanently, and loses it
*silently*, because from the broker's perspective the message was
successfully consumed (ADR-005).

### 3.4 The DLQ is terminal

`deadLetterQueueArguments()` returns exactly `{x-queue-type: quorum}` and
**has no parameter to set a TTL or an onward route with** — the policy is
unrepresentable, not merely documented (DL-034).

| Control | Value |
|---|---|
| Alert | depth > 0 **and** oldest-message age |
| Disposition | manual, expected within 14 days |
| Redrive authority | authorised operations role only |
| Redrive scope | explicit, per stat type — never blanket |
| Redrive rate | ≤ 2× nominal (2,667/s) |
| Precondition | root cause identified and resolved first |
| Mechanism | **`BUILT` (T-052, DL-138):** `./gradlew :stats-writer:redriveDlq --args="--type=… --max-messages=… --rate-per-second=… --retention-days=… --change-id=… --root-cause-resolved=true"`. Dedicated per-type broker credentials; the copy is publisher-confirmed before the DLQ original is ACKed; a record past the window is refused; the copy carries `x-stats-manual-redrive=true`, so a repeat failure goes **straight back to the DLQ** — no fresh tiers. `docs/runbooks/dlq-redrive.md` |

> **Runbook trap.** A message that sat in the DLQ long enough becomes
> `expired_on_arrival` the moment it is redriven, because its `ts.device`
> has aged past the 90-day retention window. **A redrive after ~90 days is
> a no-op that increments a counter.** Operators must know this before
> attempting one, or a failed recovery looks like a successful one
> (DL-034, DL-083). **Since T-026 that counter is real** (DL-133): the
> redriven message reaches `RetentionGuard`, increments
> `records_expired_on_arrival{type}`, is ACKed and stores nothing.

---

## 4. Persist — `stats-writer`   `PART BUILT`

> **`BUILT`:** the per-type consumer and `STAT_TYPE` selection, manual ACK after
> commit, the batch-shaped idempotent insert with duplicate metering, the counter
> range check, `passthrough` round-tripping and partition pre-creation
> (T-010 … T-012, T-019 … T-023, T-058), and — since 5 Sep 2026 — the three retry
> tiers and per-type DLQ, with the onward copy confirmed before the original is
> ACKed (T-024, DL-122); since 8 Sep 2026 — the per-type payload gate, the
> three-dialect normalisation and the non-OK `status` counter (T-057, T-063, T-064;
> DL-126…DL-128); and since 10 Sep 2026 — **retention and late arrival**
> (T-025, T-026; DL-132, DL-133). This module is now feature-complete for
> `lorastats`, and since 19 Sep 2026 serves `sysstats` the same way (T-035, DL-148).
>
> The arm is **no longer idle**: a payload that fails its per-type schema, a counter
> that will not fit a `BIGINT`, and a fourth dialect nobody has explained all throw
> `PermanentFailureException` and reach the DLQ on the first attempt, skipping the
> tiers. Both are watched since 17–19 Sep 2026: T-060 for the DLQ (DL-144), T-050 for
> the main queues, `records_expired_on_arrival` and upstream status (DL-147).
> `sysstats` runs the same flow through its own consumer, gate, guard and writer
> since T-035 (DL-148) — the gate and the guard are shared, the writer is not.

### 4.1 Per-message flow

```
consume from app_statsstore.<type>      one deployment per type,
  │                                     STAT_TYPE selects the queue
  │                                     prefetch 500  (DL-047, DL-082)
  ├─ 1. DESERIALISE       to StatsEnvelope
  │
  ├─ 1a. VALIDATE PAYLOAD StatsSchemaValidator.validate()
  │                       THE WRITER'S GATE — envelope + per-type schema.
  │                       invalid ⇒ DEAD-LETTER, never a 400: the record
  │                       was already accepted, confirmed and published,
  │                       so the caller is long gone.
  │                       All THREE lorastats dialects are ACCEPTED here
  │                       and normalised to one typed row. Selection is
  │                       STRUCTURAL — nothing on the record discriminates
  │                       them, and all three carry version: 1, so any
  │                       branch on version is wrong.
  │                       The enumeration is CLOSED at three: an unknown
  │                       FOURTH dialect still dead-letters.
  │                       (ADR-007 §4 as amended, DL-088, DL-105; T-063)
  │                       ✔ Q15a answered 4 Sep 2026, BUILT 8 Sep 2026
  │                         (DL-127). The ~15M msg/day into a no-TTL DLQ
  │                         has stopped.
  │
  ├─ 1b. UPSTREAM STATUS  status other than exactly ["conversion ok"]
  │                       ⇒ meter records_upstream_status_not_ok{type}
  │                       and WARN. STORE it anyway: the record is valid
  │                       and a redrive would repeat the verdict forever.
  │                       An ABSENT status is not counted — "did not say"
  │                       and "said it failed" are different facts.
  │                       (Q15d, DL-108, DL-128; T-064)
  │
  ├─ 2. RANGE-CHECK       netstats tx/rx: the schema permits 19 digits,
  │                       which is WIDER than signed BIGINT. Check
  │                       before conversion — an unchecked overflow
  │                       corrupts the value with no error anywhere
  │                       (DL-041, STA-04C)
  │
  ├─ 3. RETENTION GUARD   ts_device older than the window ⇒ no live
  │                       partition exists.  Skip the write,
  │                       records_expired_on_arrival{type}++, WARN, ACK.
  │                       NEVER dead-lettered: the record is valid, the
  │                       DLQ never expires, and a redrive re-derives the
  │                       same verdict forever — the record only gets
  │                       OLDER.  Returns a decision where the payload
  │                       gate throws, because this path must end in an
  │                       ACK and an exception cannot produce one.
  │                       The cutoff here and the cutoff §4.4 drops on
  │                       are the SAME function (RetentionWindow), or a
  │                       record is admitted into a partition that has
  │                       just been dropped.
  │                       (D5b, DL-112, DL-133; T-026)  ✔ BUILT 10 Sep 2026
  │                       ALERT: StatsAppLateArrival on its hourly increase
  │                       (T-050, DL-147)  ✔ BUILT 19 Sep 2026
  │
  ├─ 4. INSERT            INSERT INTO <type> (…, schema_version, received_at)
  │                       VALUES (…)
  │                       ON CONFLICT (ts_device, uid) DO NOTHING
  │
  ├─ 5. READ ROW COUNT    0 rows ⇒ records_duplicate_suppressed{type}++
  │                       A suppressed insert is METERED, NEVER SILENT
  │                       (DL-044, STA-27A)
  │
  ├─ 6. COMMIT
  │
  └─ 7. MANUAL ACK        only now  (D7)
```

**Steps 6 and 7 are in that order and it matters.** ACK before commit and
a crash between them loses the record with the broker believing it was
delivered.

### 4.2 Insert shape

Individual inserts **first**, behind a **batch-shaped interface**, so
micro-batching (100–500 records or 50–200 ms) is later a benchmarked
tuning change rather than a rewrite — with single-record fallback to
isolate a poison record and preserve per-record DLQ precision (DL-048,
G3).

### 4.3 Failure paths

| Failure | Action |
|---|---|
| Transient database error | Do **not** ACK. Publish to `retry.<tier>`, confirm that publish, then ACK the original (§3.3) |
| Retries exhausted | Publish to `.dlq`, confirm, then ACK. Log ERROR, alert |
| Duplicate `(ts_device, uid)` | Not a failure — `DO NOTHING`, meter, commit, ACK |
| `ts_device` past retention | Skip, meter `records_expired_on_arrival{type}`, `WARN`, ACK. **Never a dead-letter** (`BUILT`, DL-133). Alerted by `StatsAppLateArrival` (`BUILT`, DL-147) |
| Counter exceeds `BIGINT` | Reject to DLQ. Never truncate silently |
| Partition missing | **Insert fails.** Pre-creation is on the critical path (§4.4) |

### 4.4 Partition and retention flow   `BUILT` (T-012, T-025; whole-window pre-creation DL-149)

Both run **inside `stats-writer`**, both behind the same per-type advisory
lock, because N replicas per type would otherwise race (DL-045). One cron,
one entry point, one order — pre-create **then** retain, so tomorrow's
partition exists before yesterday's is dropped.

```
scheduled tick   stats.retention.schedule, 03:30 UTC
  │              types taken at their own stagger offset, derived from the
  │              TYPE — so it separates five deployments in production and
  │              five passes in `all` mode, with one mechanism
  │
  ├─ SELECT pg_try_advisory_lock(hashtext('stats.retention:' || :statType))
  │     not acquired ⇒ skip this run, log DEBUG, done
  │
  ├─ PRE-CREATE      ensure a daily partition for EVERY day in
  │                  [cutoffDay, today + pre-create-days] — both ends from
  │                  RetentionWindow (DL-149). Not only the days ahead: a
  │                  valid record from a gateway back after weeks offline
  │                  needs a partition older than this deployment, and
  │                  a missed run must be healed, not left as a hole.
  │                  If this fails, INSERTS FAIL. Not a nicety.
  │                  pg_partman is NEVER a dependency (DL-027)
  │
  └─ RETENTION       0. FINALIZE any detach an earlier run left pending
                        — see the hazard below; without this the sweep is
                        wedged and nothing else looks wrong
                     1. for each partition wholly older than the window,
                        whose NAME we produced and whose CATALOGUE BOUND
                        matches that name:
                          ALTER TABLE … DETACH PARTITION … CONCURRENTLY
                          DROP TABLE …
                     2. sweep orphans — detached but never dropped
                     3. publish retention_oldest_partition_age_days{type}

                     A catalog operation — milliseconds, no dead tuples,
                     no vacuum debt.  NOT row deletion (DL-042)
```

**Three PostgreSQL facts this is built around** (verified against PG 17,
DL-132). `DETACH … CONCURRENTLY` **cannot run inside a transaction block** —
a hard `ERROR`, so the sweep runs auto-commit. An **interrupted** detach
leaves the partition *pending detach*, and from then on **every** detach on
that parent fails — **while `INSERT` and pre-creation keep working**, so a
run killed by a rolling deploy wedges retention permanently and the only
symptom is disk. And between the detach and the drop the table still exists
holding its rows, so a crash there leaks a day of storage that nothing would
reclaim: hence step 2, counted separately as
`retention_orphans_dropped{type}` because an ordinary drop counter would
read as retention working.

**Nothing is dropped that has not been proved droppable.** Name we produced,
bound that matches the name, day wholly outside the window — all three, or
the partition is logged and kept. Dropping is irreversible short of a
per-partition restore (ADR-003 D5).

**Retention keys on `ts.device`, and that has a consequence.** A gateway
that was offline delivers records whose `ts.device` is weeks older than
`ts.server` — near expiry the moment they arrive. This is the
**answered and accepted business rule**. Do not "fix" it by quietly
switching to `ts.server`.

### 4.5 Table shape

```
<type>  PARTITION BY RANGE (ts_device), daily partitions
  ├─ uid            text        ─┐
  ├─ ts_device      timestamptz  ├─ PRIMARY KEY (ts_device, uid)
  │                              │  in THAT column order, per partition
  ├─ tenant         text         │  (DL-043, G2)
  ├─ maintenant     text        ─┘  deliberately NOT in the key (DL-044)
  ├─ clsbox         text        12 UPPERCASE hex, normalised by the
  │                              Writer with a table CHECK behind it
  │                              (DL-115). NOT `gateway` — the SRS name
  │                              was never on the wire (ADR-007 §1)
  ├─ ts_server      timestamptz
  ├─ received_at    timestamptz
  ├─ schema_version smallint  NOT NULL   which schema validated it (DL-046)
  └─ …type-specific typed columns…
      netstats only: data.timespan typed; interface map in jsonb (DL-041)

indexes
  (tenant, clsbox, ts_device DESC, uid DESC)   the dominant query
  (tenant, uid)                                 the uid-only lookup,
                                                which cannot prune partitions
```

---

## 5. Read — `stats-api`   `SPECIFIED`, contract `BLOCKED` on D-09 (three operations since DL-151; the device paths are T-067)

### 5.1 Request flow

```
GET /v1/stats/{type}?from=…&to=…&limit=…&cursor=…        tenant-wide list
GET /v1/stats/{type}/{clsbox}/{from}[/{to}]?limit=…&cursor=…   one gateway (DL-151)
GET /v1/stats/{type}/{uid}                                one record
Authorization: Bearer <token>

  The device-scoped forms were added 21 Sep 2026 at management's request —
  "MAC address and timestamp". {clsbox} IS the MAC. The one-argument form is
  OPEN-ENDED (ts_device >= from), not to = now: Input admits ts.device up to
  5 min ahead of arrival, so a literal "now" hides a fast clock's newest record.
  The (tenant, clsbox, ts_device DESC, uid DESC) index serves it; no window
  limit applies, the page size bounds each request. Tenant predicate identical.
  │
  ├─ 1. NOT A GET?           ⇒ 405, no side effect, on EVERY route (DL-070)
  │
  ├─ 2. VALIDATE TOKEN       signature, exp, iss (string compare, never
  │                          fetched), aud = stats-api
  │                          fail ⇒ 401
  │
  ├─ 3. ROLE                 stats.readOnly required   ⇒ else 403
  │
  ├─ 4. TENANT CLAIM         single-valued String `tenant`
  │                          absent or empty ⇒ 403 — NEVER "all tenants"
  │                          (DL-061; note: ADR-006 wins over the 401 in
  │                          an older openapi.yaml)
  │
  ├─ 5. VALIDATE QUERY       from/to REQUIRED on the list — there is no
  │                          unbounded tenant-wide query (DL-071); the
  │                          device forms take them from the path, and the
  │                          one-argument form has no upper bound by design
  │                          clsbox not 12 hex ⇒ 400 invalid_parameter
  │                          inverted range ⇒ 400 invalid_time_range
  │                          limit 1–1000, default 100
  │                          cursor filters ≠ request filters
  │                                        ⇒ 400 invalid_cursor
  │
  ├─ 6. QUERY                tenant predicate IS IN THE SQL — always.
  │                          Never filter across tenants in memory.
  │                          ORDER BY ts_device DESC, uid DESC — fixed,
  │                          always applied, never caller-negotiable
  │                          Keyset seek from the cursor, never OFFSET
  │                          (DL-060, DL-072)
  │
  └─ 7. RESPOND              200 + items + nextCursor
                             (null/absent ⇒ last page)
```

### 5.2 The response rules that exist to prevent an oracle

| Situation | Response | Why |
|---|---|---|
| Authenticated, no data in range | **200**, empty `items` | 403 or 404 would disclose that data exists elsewhere |
| `uid` in another tenant | **404** | Identical in status, body, headers **and timing class** to a `uid` that does not exist at all |
| `uid` does not exist | **404** | Same response, necessarily |
| No `tenant` claim | **403** | Authenticated, but nothing is in scope |
| Any non-GET | **405** | No write path exists in this module |

**The information leak would be the *difference*.** A 403 for "exists but
not yours" versus a 404 for "does not exist" turns the API into an
enumeration oracle — an attacker learns which records exist in other
tenants without ever reading one. Because timing is part of the
observable, this cannot be a late check on an already-fetched row
(DL-062).

### 5.3 Pagination walk

```
page 1:  GET …?from=X&to=Y&limit=100
         → items[100], nextCursor = "…"

page 2:  GET …?from=X&to=Y&limit=100&cursor=…      ← from/to/gateway RESENT
         → items[100], nextCursor = "…"

last:    → items[n], nextCursor = null
```

The cursor **carries the filters it was issued for**. Changing one
mid-walk returns `invalid_cursor` rather than silently returning a page
from a different query (DL-072).

---

## 6. Startup flow (per module)   `PART BUILT` (readiness groups are `BUILT` — T-006, DL-103)

```
stats-input                    stats-writer                stats-api
  │                              │                           │
  ├─ load config                 ├─ load config              ├─ load config
  ├─ auth.mode UNSET             ├─ read STAT_TYPE           ├─ Keycloak
  │    ⇒ FAIL TO START           │  (`all` = dev only)       │   resource server
  ├─ mode=none ⇒ WARN            │                           │
  ├─ prod + none ⇒ REFUSE        ├─ Flyway migrate           ├─ NO broker
  │                              │                           │   connection —
  ├─ declare topology            ├─ declare topology         │   this absence is
  │  from AmqpTopology           │  from AmqpTopology        │   itself an
  │  (identical, idempotent)     │  (identical, idempotent)  │   assertion (§3.1)
  │                              │                           │
  ├─ publisher confirms          ├─ prefetch, manual ACK     ├─ readiness:
  │  + ReturnsCallback           │                           │   DB only
  ├─ readiness: BROKER only      ├─ readiness: BOTH          │
  │  (no DB dependency, rule 2)  │  broker + DB              │
  └─ liveness: separate          └─ liveness: separate       └─ liveness: separate
```

**Readiness sets differ per module and that is deliberate** — a copied
readiness block is a bug (DL-026). A liveness probe that fails on a broker
blip restarts the container instead of removing it from the load balancer
for thirty seconds.

**Topology declarations must be byte-identical between Input and Writer**,
which is why both build every field — durability and auto-delete included, not
just names and arguments — from `AmqpTopology`'s definitions. `AmqpDeclarationAssert`
in `stats-testsupport` is the single assertion both modules' tests call, and
`stats-api` asserts it cannot load an AMQP type at all. T-032 is **DONE**
(DL-035, DL-123).

---

## 7. Build and test flow   `BUILT`

```
./gradlew build
  │
  ├─ stats-common processResources
  │     copies docs/schemas/*.schema.json  →  generated output /schemas
  │     NO second copy is checked in — a checked-in copy can drift,
  │     and a drift means the Java and Python validators disagree
  │     about what a valid record is, silently (DL-054)
  │
  ├─ test source set adds docs/schemas/fixtures as a resource dir
  │     the 68 normative fixtures, read directly
  │
  ├─ systemProperty stats.repositoryRoot = <repo>
  │     StatTypeFanOutTest reads the OTHER modules' sources to prove
  │     no code special-cases an individual stat type (STA-22)
  │
  └─ tests
        SchemaFixtureParityTest    Java validator ≡ validate-fixtures.py
        AmqpTopologyTest           names + argument maps, incl. the
                                   assertion that the DLQ map is exactly
                                   {x-queue-type: quorum}
        StatsEnvelopeFixtureTest   round-trip against real fixtures
        TimestampsTest             seconds ↔ Instant, both directions
        …
```

CI runs `./gradlew build` plus Testcontainers integration tests on every
push, and **fails on a failed schema-fixture assertion** (DL-015).

### Fixture layout

```
docs/schemas/fixtures/
  valid/      the happy paths, plus tenant-isolation pairs where
              tenant != maintenant  — a fixture with them equal proves
              nothing about isolation (DL-060); since DL-146 also the
              live shapes: 2G ltestats without rsrp/rsrq, etxt-only
              ltestats, meta/ts.deviceOrig, expiresOn as {"$date": …}
  invalid/    missing fields, wrong types, ts.converter, ts in
              milliseconds, counter overflow, IPv6
  edge/       clock skew, late arrival, replay duplicate,
              retention boundary — some are SCHEMA-VALID and carry
              $expect: "input-reject", because the rule under test is
              code in Input, not a schema rule (DL-052)
  malformed/  truncated body, top-level array, duplicate keys,
              deep nesting, oversized string, JSON null
```

---

## 8. Where a record can be lost — the complete list

Every one of these was designed against. Each row names the control and
what happens if the control is removed.

| # | Window | Control | If removed |
|---|---|---|---|
| 1 | 200 returned before broker confirm | Confirm, **then** respond | Broker failure in the window loses the record, and Heimdal never retries because it saw success |
| 2 | Broker ACKs a publish no queue is bound to | `mandatory=true` + `ReturnsCallback` ⇒ 503 | Record dropped on the floor with every component reporting success |
| 3 | ACK before the database commit | Manual ACK **after** commit | Crash between them loses the record; broker believes it was delivered |
| 4 | ACK the original, then publish to retry | Publish → **confirm** → then ACK | Crash in the window loses it silently |
| 5 | DLQ expiry | **No TTL, ever** — unrepresentable in the API | The last remaining copy of the record is deleted on a timer |
| 6 | Duplicate suppressed with no counter | `records_duplicate_suppressed{type}` | A cross-tenant `uid` collision becomes invisible |
| 7 | Late arrival, no live partition | Skip + meter + ACK — **BUILT 10 Sep 2026 (T-026, DL-133)**; the **alert** on `records_expired_on_arrival` is **BUILT 19 Sep 2026 (T-050, DL-147)** | Either a silent drop, or a write into a partition that does not exist. Q15b (DL-106) confirms `ts.device` is genuine device time, so this path is real |
| 8 | Counter wider than `BIGINT` | Range-check before conversion | Silent value corruption, no error anywhere |
| 9 | In-flight transformation of any kind | **Byte-identical publication** | Per-partition duplicate suppression stops working, silently |
| 10 | Unsorted pagination | Fixed `(ts_device DESC, uid DESC)` | A row returned twice or skipped entirely, with no error |
| 11 | Redrive after ~90 days | `DlqRedriveCommand` **refuses** a record past the window (T-052, DL-138), and the runbook says to watch `records_expired_on_arrival` while redriving | A no-op that increments a counter — and looks like a successful recovery |
| 12 | **Payload validated at Input instead of the Writer** | Two gates: `validateEnvelope()` at Input, `validate()` at the Writer (ADR-007 §5) | A dialect mismatch becomes a 400. Heimdal retries only on a non-2xx, so the record is **destroyed** where a dead-letter would have been redrivable. The one loud window on this list — and loud does not mean recoverable |
| 13 | **DLQ depth alert drowned by routine dialect volume** — *closed 8 Sep 2026 (DL-127)* | Per-type DLQ thresholds (ADR-002 §12). **All three `lorastats` dialects are now accepted**, so the routine volume is gone and T-060 can be sized against real failure | ~15M lorastats messages/day used to sit permanently above a uniform threshold, so a genuine dead-letter in another type was never noticed. **Closed by the build, not by the decision** — DL-105 decided it on 4 Sep and it stayed live for four more days. Any future decision that dead-letters a type by design reintroduces it whole |
| 14 | **A retention run killed mid-detach** — *closed 10 Sep 2026 (DL-132)* | Every run begins with `ALTER TABLE … DETACH PARTITION … FINALIZE` | This is not a loss of a record; it is a loss of the *mechanism*. An interrupted `DETACH CONCURRENTLY` leaves the partition pending detach, and from then on every detach on that parent fails — **while `INSERT` and pre-creation keep working perfectly.** Retention stops, ingestion looks healthy, and the only symptom is disk. The gap between detach and drop is the same shape: a crash there leaks a day of rows nothing would reclaim, which is why `retention_orphans_dropped{type}` is its own series |
| 15 | **A valid in-window record with no partition for its day** — *closed 20 Sep 2026 (DL-149)* | Every maintenance pass ensures `[cutoffDay, today + pre-create-days]` from `RetentionWindow`, at startup and daily | Ahead-only pre-creation left every retained day before first deployment, and every day a missed run skipped, with no partition. The INSERT fails with SQLSTATE 23514, which is **permanent**, so the record goes straight to a DLQ that never expires and a redrive re-derives the same failure. It is the offline-gateway record ops most wants, `records_persisted` simply does not move, and `records_write_failed` says nothing about why |

**Twelve of the fifteen fail silently.** Window 12 is the exception worth
studying: it is fully logged, fully visible, returns a correct-looking
`400 invalid_type` — and still destroys the record, because the loss is in
what the *caller* does with a well-formed answer. Visibility is not
recoverability. That is the reason this project's
rules read as strictly as they do, and the reason
`IMPLEMENTATION_RULES.md` §9 now quizzes a change before it is made
(DL-005).

---

## 9. Adding a new stat type — the additive path

A new type must require **no change to any existing type** (STA-14A,
STA-22, rule 6). Everything derives from `StatType.wireName()`.

```
1. docs/schemas/<type>-v1.schema.json       normative schema
2. fixtures: valid / invalid / edge / malformed
3. StatType enum entry                       ⇒ routing key, queue names,
                                               retry + DLQ names, schema
                                               filename, table name all
                                               follow automatically
4. Flyway migration: table + partitions + indexes
5. stats-writer deployment with STAT_TYPE=<type>
6. openapi.yaml: the type enum
7. metrics: the {type} label appears by itself
```

**If any of this requires editing existing type handling, the abstraction
is wrong.** That is a design smell to raise, not to work around.
`StatTypeFanOutTest` exists to catch it.

---

## 10. Cross-references

| For | Read |
|---|---|
| Why any of this is the way it is | `claude/DECISIONS.md` |
| Project context, rules, and traps | `claude/CLAUDE.md` |
| Whether a phase may start | `claude/CODE_START_CHECKLIST.md` |
| What is still open, and who owns it | `claude/OPEN_QUESTIONS_STATUS_29AUG2026.md` |
| The AMQP contract in full | `docs/contracts/amqp-topology-v1.md` |
| The inbound HTTP contract | `docs/contracts/heimdal-input-contract-v1.md` |
| The API contract | `docs/contracts/openapi.yaml` |
| Repeatable procedures | `claude/SKILLS.md` |
