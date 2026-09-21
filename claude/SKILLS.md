# SKILLS.md

Reusable, repeatable procedures for the Stats App. Each skill is a checklist
for a task that will be done more than once — the point is that the *second*
time it is done, it is done the same way as the first.

> **Version 1.5 — 21 September 2026.** Skill 1 step 6 names the **three** operations a
> type has since DL-151 (list, device-scoped, `uid`) and the demo step to extend; Skill 4
> gains the triage question the first end-to-end run taught — *is the Writer logging at
> all?* (DL-150); Skill 5 says pre-creation covers the whole window (DL-149); Skill 6
> uses the DL-151 change as its worked example; Skill 8 gains the MDC rule the build now
> enforces.
>
> **Version 1.4 — 12 September 2026.** Skill 4 now points at the built redrive
> (T-052, DL-138) and the built gauges (T-027, DL-137) instead of describing them.
>
> **Version 1.3 — 5 September 2026.** Skill 1 and Skill 3 rebuilt against the
> reference slice **as it now exists in the repository**, and against the two changes
> that make the old wording wrong: the **two validation gates** (ADR-007 §5 — an
> envelope failure is a 400, a *payload* failure is a **dead-letter**, so "missing
> payload field → 400" is no longer true) and the columns every new table must carry
> — `passthrough`, **`data_extra jsonb`** (DL-095) and the **`clsbox` CHECK**
> (DL-115).
>
> **Version 1.2 — 16 August 2026.** Updated for **PostgreSQL**. The v1.0 version
> of this file described MongoDB TTL indexes and left C1, Q10, and Q23 open; all
> three are now answered. Retention keys on **`ts.device`**.

---

## Skill 1 — Add a new stat type

The single most repeated task on this project, and the one the whole
architecture is designed around. If this procedure ever requires modifying
an existing stat type's code, **stop** — that violates STA-14/STA-22.

### Prerequisites
- [ ] The schema for the new type is documented (fields, types, mandatory/optional)
- [ ] The type name is confirmed and matches the routing key exactly
- [ ] **T-033 is green.** The broker/database outage drill is Phase 3's entry criterion:
      fan out before the reference slice is proven and you get five divergent
      implementations to reconcile
- [ ] You have read the reference slice you are copying — **copy the `sysstats` slice**
      (T-035, DL-148): `SysstatsConsumerDescriptor`, `SysstatsConsumerProperties`,
      `SysstatsDeploymentCondition`, `SysstatsConsumerConfiguration`, `SysstatsConsumer`,
      `SysstatsMessageProcessor`, `SysstatsBatchWriter` and `V6__sysstats_table.sql`. It is
      the slice written *after* the shared beans were shared, so it declares exactly its
      own resources; `lorastats` reached the same shape across V1–V5 and three later
      corrections. Three things are shared and must NOT be redeclared or copied:
      `WriterAmqpConfiguration` (the two exchanges, the processor selector, the two
      declaration helpers), `StatTypeDeployment` (the `stats.writer.stat-type` reading),
      and the type-agnostic `PayloadGate` / `RetentionGuard`
- [ ] Your writer declares its bean as `@Component(<Type>ConsumerDescriptor.BATCH_WRITER_BEAN)`
      and your processor injects `StatsBatchWriter` with `@Qualifier` on that name. An
      unqualified `StatsBatchWriter` injection is ambiguous the moment a second writer
      exists and fails the context in `all` mode — this is how adding sysstats would have
      broken lorastats (DL-148)
- [ ] Your consumer calls `queueAge.observe(TYPE, message)` at listener entry, before any
      work — the oldest-message-age sample T-050's alerts read (DL-147)
- [ ] Add your descriptor to `StatTypeFanOutTest.JUSTIFIED_SPECIAL_CASES` and your writer
      to `VersionIsNotADiscriminatorTest.JUSTIFIED_READS`, each with its reason — that is
      the reviewed-decision point both guards exist to create

### Steps

1. **Define the payload model**
   - Add the `data` payload class. The envelope is shared — do not redefine it.
     `StatsEnvelope` and `Timestamps` are lossless already and must not be touched.
   - Mandatory envelope fields: `uid`, `tenant`, `maintenant`, `clsbox`, `type`, `ts.server`, `ts.device`
   - **Parse the wire types this type actually sends, not the ones the SRS implies.**
     `timespan` is an integer in `lorastats`/`omsstats`/`netstats` and the string
     `"300"` in `sysstats`; every `ltestats` numeric is a string **and `ltestats`
     `data` is a closed `oneOf` of two shapes** — a measurement with `rsrp`/`rsrq`
     optional, or `{"etxt": …}` alone from a modem that never registered (DL-146);
     `netstats` counters
     are digit-only strings up to 19 digits. Range-check against the signed `BIGINT`
     maximum **before** conversion and dead-letter on overflow — never zero, never
     truncate *(T-062, STA-04C)*

2. **Register validation** *(Module 1, STA-03)*
   - Add the schema to the validation registry keyed by type name
   - Confirm an invalid payload returns **HTTP 400** and is logged
   - Confirm an unrecognised type is rejected, not silently forwarded
   - Nothing about clock-skew rejection changes per type: Input applies
     `stats.input.validation.max-clock-skew` to the envelope, so a new type inherits
     the 400 `clock_skew_exceeded` behaviour with no code *(ADR-003 D9, STA-03B)*

3. **Declare the queue and binding** *(Module 2, STA-10A)*
   - Main queue `app_statsstore.<type>`, three retry tiers
     `app_statsstore.<type>.retry.<tier>`, dead-letter queue
     `app_statsstore.<type>.dlq` — one set per type (C1 answered; names per G1/G4)
   - Bind to the `app_stats_input` topic exchange with the type as routing key
   - Set `durable = true`; publish persistent messages *(STA-13A)*
   - Configure the dead-letter exchange `app_stats_dlx` and the retry limit *(STA-12A)*
   - Declare from the shared constants in `stats-common`, identically in every
     broker-connected module, or startup fails with `RESOURCE_LOCKED`
   - Take the declaration arguments from `AmqpTopology.mainQueueArguments()`,
     `retryQueueArguments(type, tier, delay)` and `deadLetterQueueArguments()` —
     **never assemble a map by hand.** The DLQ map has no TTL parameter on purpose
     *(ADR-002 §12, trap 17)*

4. **Add the consumer** *(Module 2, STA-20A)*
   - One consumer per type — do not extend an existing consumer to handle two
   - Manual ACK **after** the successful PostgreSQL transaction *(STA-25A)*
   - Per-type concurrency and prefetch, externally configured *(STA-11A)*
   - Failure path must dead-letter with a log entry *(STA-25A)*

5. **Add the table, migration, and indexes** *(Module 2, STA-21A)*
   - New table named after the stat type, created by a **versioned migration**
   - Common envelope columns in the **production** names — `clsbox`, `ts_server`,
     `ts_device`, `tenant`, `maintenant`, `uid`, `record_version` — plus the type's
     `data` representation
   - A nullable **`passthrough jsonb`** for `ts.datastore`, `ts.converter`,
     `ts.expiresOn` (string or `{"$date": …}` — store what arrived), `ts.deviceOrig`,
     `status` and `meta`, and a nullable **`data_extra jsonb`** for unmapped
     `data` members. **Omitting `data_extra` silently reopens a data-loss path**
     *(DL-094, DL-095)*
   - **`CHECK (clsbox ~ '^[0-9A-F]{12}$')`, and the Writer uppercases before the
     `INSERT`** — one canonical case, by normalisation and never by rejection. The
     envelope schema still accepts either case, so a lowercase record is never 400'd
     *(DL-115)*
   - `uid` as its own column, with `PRIMARY KEY (ts_device, uid)` **in that column
     order** — scoped per partition, not global *(STA-21B, ADR-003, G2)*
   - Insert with `ON CONFLICT (ts_device, uid) DO NOTHING`, and **increment
     `records_duplicate_suppressed{type}` on a suppressed row** — never silent
   - Index `(tenant, clsbox, ts_device DESC, uid DESC)` — the dominant query
     pattern with a unique tie-breaker for keyset paging *(STA-21C)*
   - Retention keyed on `ts.device`, period read from `application.properties`
     *(STA-23A, STA-23B)*
   - Daily partitions pre-created ahead of ingestion by the writer's own scheduled
     task, behind the `pg_try_advisory_lock` — **never `pg_partman`**, which is not a
     dependency of this project *(ADR-003 D1)*
   - Dominant filters must be indexed columns, never JSON path scans

6. **Expose the API endpoint** *(Module 3, STA-33)*
   - GET only *(STA-32)*
   - **All three operations**, because `{type}` is a path parameter on each and a type
     that answers one but not another is a contract violation: the tenant-wide list
     `/v1/stats/{type}`, the device-scoped `/v1/stats/{type}/{clsbox}/{from}[/{to}]`
     (DL-151, T-067 — open-ended one-argument form, foreign `clsbox` is an empty 200,
     no window limit) and `/v1/stats/{type}/{uid}`
   - Same filter parameters as every other type *(STA-34)*
   - Tenant scoping applied server-side *(STA-35)*
   - Pagination consistent with other types *(STA-36)*
   - Update the OpenAPI spec — the per-type `data` schema is inlined there and
     `check_schema_drift.py` fails CI if it disagrees with `docs/schemas/` *(STA-37, DL-140)*

6a. **Add the type to `docker/demo.sh`'s happy-path step** *(T-069)* — the demo is the
   one place every §5 rule is exercised together against real infrastructure, and a type
   it does not post is a type nobody has watched go end to end.

7. **Test** — see Skill 3

8. **Document**
   - Add the type to `CLAUDE.md` §4
   - Record its retention period if it differs from the default

Two things the copy **inherits from shared code** since 20 Sep 2026 and must not re-add:
partitions for the whole window (`PartitionMaintenance`, DL-149 — nothing per type), and
the identifiers on every log line (`WriterLogContext`, DL-150 — an `addKeyValue` of `uid`,
`statType`, `tenant`, `clsbox`, `traceId` or `errorCode` in the new processor or writer
**fails the build**; the scope logs the identifiers, you log the event).

### Definition of done
- [ ] No existing stat type's code was modified
- [ ] Invalid payloads for the new type return 400, and nothing is published
- [ ] A 2xx is returned only after publisher confirm; broker down returns 503
- [ ] A forced write failure lands in **this type's** DLQ
- [ ] A duplicate `uid` redelivery is a harmless no-op
- [ ] A poison message in this type does not stall any other type
- [ ] A cross-tenant read returns nothing
- [ ] Expired rows are removed; rows inside the window are not

---

## Skill 2 — Review a change for tenant isolation

Run this on **every** change touching a query, a controller, or a security
config. Tenant isolation is a security control, not a feature.

### Checklist

- [ ] Is the tenant value derived from the **validated token**, never from a
      request parameter, header, or body field?
- [ ] Can a caller override the tenant by supplying a `tenant` query
      parameter? (They must not be able to.)
- [ ] Is the tenant filter applied in the **query itself**, not by filtering
      results after retrieval?
- [ ] Is the tenant filter applied to **every** query path, including counts,
      aggregates, and pagination cursors?
- [ ] Does the pagination cursor leak information about records the caller
      cannot see?
- [ ] Is the correct field used? **It is `tenant`, always** — `maintenant` is
      never the isolation field. *(Answered: Q23, Q5, ADR-006.)*
- [ ] Does a test exist where `tenant` and `maintenant` **differ**?
- [ ] Does an error message reveal the existence of another tenant's data?
      (404 and 403 can leak; prefer returning empty results.)

### Red flags
- Any `if` statement deciding whether to apply the tenant filter
- A tenant value passed as a method parameter from a controller
- A repository method with no tenant argument
- Tests where every fixture has `tenant == maintenant`

---

## Skill 3 — Write tests for a stat type

### Unit — validation: TWO GATES, TWO CONSEQUENCES *(ADR-007 §5)*
- [ ] Valid record accepted
- [ ] Each mandatory **envelope** field missing → **400**, nothing published *(Input)*
- [ ] `ts.device` beyond `stats.input.validation.max-clock-skew` → 400
      `clock_skew_exceeded`, metered *(ADR-003 D9)*
- [ ] Each mandatory **payload** field missing → **dead-letter at the Writer**, never a
      400 — by then the record has been accepted, confirmed and published, and Heimdal
      retries only on a non-2xx *(the fixtures carry `$expect: "writer-dlq"`)*
- [ ] Wrong data type in a payload field → dead-letter; in an envelope field → 400
- [ ] Unknown stat type → 400
- [ ] Extra unexpected fields → confirm intended behaviour (reject or ignore)
- [ ] `ts` in **seconds**, not milliseconds
- [ ] The published message is **identical** to what was received — no transformation
- [ ] An `omsstats` record with `ts.server` passes unchanged; no `ts.converter`
      handling exists *(ADR-004)*

### Integration — messaging *(Module 1 → 2)*
- [ ] Published with the correct routing key
- [ ] Lands in the correct per-type queue, and only that one
- [ ] 2xx returned **only after** publisher confirm *(STA-04A)*
- [ ] Broker unavailable or confirm timeout → **503**, nothing published *(STA-06A)*
- [ ] Consumed and written to the correct table
- [ ] Simulated write failure → message reaches that type's DLQ *(STA-12A)*
- [ ] Retry limit respected, not infinite
- [ ] Message survives a broker restart *(STA-13A)*
- [ ] A poison message in one type does **not** stall other types *(STA-10B)*

### Integration — persistence *(Module 2)*
- [ ] Written to the type's own table, not a shared one
- [ ] ACK happens **after** the successful transaction, never before *(STA-25A)*
- [ ] A rolled-back transaction does not ACK
- [ ] Duplicate `uid` redelivery is an idempotent no-op, not a DLQ *(STA-25A)*
- [ ] Expired rows removed, keyed on **`ts.device`** *(STA-23B)*
- [ ] A late-arriving record with an old `ts.device` behaves as documented —
      near-immediate expiry is correct, not a bug
- [ ] Retention value read from configuration, not hard-coded
- [ ] Verified against a real PostgreSQL Testcontainer, not a mock

### Integration — API *(Module 3)*
- [ ] Unauthenticated request → 401 *(STA-31)*
- [ ] Authenticated without the required role → 403
- [ ] POST/PUT/PATCH/DELETE → 405, on every endpoint *(STA-32)*
- [ ] Time range filter works at both boundaries
- [ ] **Cross-tenant read returns nothing** — with differing `tenant`/`maintenant`
- [ ] Pagination returns each record exactly once, none skipped
- [ ] Pagination stable when new records arrive mid-iteration

### Load *(before go-live)*
- [ ] Sustained ingestion at the average rate: 267 records/sec/type, 1,333/sec
      across five types
- [ ] Sustained ingestion at the **burst** rate: **13,333/s**, the 10× design
      target from assuming synchronised 10-minute boundaries *(Q13a, answered
      18 Aug 2026)*. Writer is sized for drain (~2,000/s per type), not for peak
- [ ] Queue depth spikes each cycle and drains — **this is the buffer working.**
      Alert on *oldest message age*, never on depth
- [ ] Replay at the requested 2× cap (2,667/s) on top of live traffic, and
      `records_duplicate_suppressed` spikes without an alert firing *(Q13c — the 2× cap is agreed since 4 Sep 2026, DL-109, and still unenforceable by us)*
- [ ] Behaviour when consumption is slower than ingestion — queue grows, nothing lost
- [ ] Behaviour when the database is unavailable — messages buffer, then drain
- [ ] Retention/deletion lag under sustained write load; table and index growth

---

## Skill 4 — Diagnose a stuck or growing queue

The pipeline's defining failure mode: it fails **silently**. The endpoint
keeps returning 200 and RabbitMQ keeps accepting messages while nothing is
being written.

### Triage order

1. **Which queue?** Per-type queues mean the affected type localises the fault.
2. **Is the consumer alive?** Check process health and consumer count on the queue.
   Zero consumers on a growing queue is the answer. **Since T-027 (DL-137) the Writer
   exports this:** `consumer_availability{type}`, `queue_depth{type}` and
   `dlq_depth{type}` on `/actuator/prometheus`, polled every 30 s and cached. **Read
   `broker_metrics_collection_success` first** — a failed poll writes NaN, not zero,
   and an alert that treats a stale zero as "empty" is the outage that looks quiet.
3. **Is the Writer logging at all?** Counters moving with no `WARN` lines beside them
   is not "quiet" — since DL-150 it is the signature of a log line the ECS encoder
   refused. Look for `Appender [CONSOLE] failed to append … Duplicate nested pairs` in
   the *first* lines of the process's output: logback reports four such failures and
   then drops silently. The build-failing guard covers `stats-writer`; if the symptom
   appears in `stats-input`, the guard's `MODULE` is the one-line fix.
4. **Is the database reachable?** Connection pool exhaustion looks like a slow
   consumer, not an outage.
5. **Is the DLQ growing too?** DLQ growth means messages are being processed
   and *rejected* — a data or schema problem, not a capacity problem.
6. **Is ingestion abnormally high?** Compare against the expected rate — 267/sec
   per type, 1,333/sec across five types. Since DL-151 every Input series carries `source="internal"|"external"` — a burst on `external` is a different incident from a Heimdal replay. A Heimdal replay after an outage
   produces a legitimate burst; Heimdal owns dead-lettering and retries anything
   we answered with a non-2xx.
7. **Is one message poisoning the consumer?** Same message repeatedly
   retried and failing. Check the retry counter.
8. **Is retention the real problem?** A bloated table or a long-running deletion
   job slows writes and looks exactly like a slow consumer.
9. **Is the DLQ full of `23514`?** SQLSTATE `23514` — *no partition of relation found
   for row* — dead-letters as permanent on the first attempt. Since DL-149 every pass
   pre-creates the whole window, so this now means the maintenance pass itself is not
   running (advisory lock held by a dead replica? `retention_oldest_partition_age_days`
   climbing?), not that a record was "too old" — that record would have been skipped by
   `RetentionGuard`, not dead-lettered.

### After resolution
- [ ] Redrive DLQ messages once the root cause is fixed — an authorised operations
      role only, explicit, traceable, scoped per type, and rate-limited to 2× nominal
      (2,667/s). Blind automatic replay is not permitted. *(ADR-002 §12)*
      **The command exists since T-052 (DL-138):** `./gradlew :stats-writer:redriveDlq`
      with `--type`, `--max-messages`, `--rate-per-second`, `--retention-days`,
      `--change-id` and `--root-cause-resolved=true`, under dedicated per-type broker
      credentials. It refuses a record past the window, confirms the copy before ACKing
      the original, and marks the copy `x-stats-manual-redrive=true` so a repeat failure
      goes straight back to the DLQ. **Follow `docs/runbooks/dlq-redrive.md`** — one-record
      pilot first, evidence file, stop conditions — rather than running it from memory.
- [ ] Check the age of what you are about to redrive. **A message that has sat in the
      DLQ for ~90 days is past the retention window: redriving it produces
      `expired_on_arrival`, not a recovery.** The counter goes up and the row never
      lands. Confirm the `ts.device` range before you replay anything old.
      **Since T-026 (10 Sep 2026, DL-133) this is observable rather than theoretical:**
      the redriven message reaches `RetentionGuard`, increments
      `records_expired_on_arrival{type}`, logs a `WARN` naming the `uid`, `clsbox` and
      the record's age in days, and is ACKed. Watch that counter while you redrive — if
      it moves in step with the redrive, you are not recovering anything.
- [ ] Confirm no data was lost
- [ ] If the alert did not fire, fix the alert threshold. **Alert on DLQ depth > 0 and
      on oldest-message age — never on main-queue depth**, which spikes every cycle by
      design *(Q13a)*. The DLQ has no TTL, so nothing disappears while you decide.
      **Both exist since T-060/T-050 (DL-144, DL-147):** `ops/prometheus/dlq-alerts.yml`
      and `ops/prometheus/writer-alerts.yml`. Read the three main-queue rules as one
      alert — `StatsAppMainQueueAging` is sampled at delivery and cannot move when nothing
      is delivered, which is what `StatsAppMainQueueNoConsumer` and
      `StatsAppConsumerStalled` are for. If `StatsAppLateArrival` fires *during* a
      redrive, stop: that is the ~90-day trap above, observed.

---

## Skill 5 — Add or change a retention policy

Retention keys on **`ts.device`** — the device's own timestamp, not the time we
received the record *(STA-23B, answered D9)*. Default 90 days.

**The mechanism is detach-and-drop, not deletion** *(ADR-003 D1; built by T-025,
DL-132)*. Nothing here ever issues a `DELETE`. Once a day, per stat type, behind
`pg_try_advisory_lock`, `stats-writer` pre-creates the partitions ahead and then, for
each daily partition wholly past the window, runs `ALTER TABLE … DETACH PARTITION …
CONCURRENTLY` followed by `DROP TABLE`.

### Steps
1. Confirm the intended period with the requester — it affects storage cost
2. Set it in `application.properties`, never in code *(STA-23A)*:
   `stats.retention.default-days`, or `stats.retention.days-by-type.<wire-name>` for
   one type. A per-type key that does not name a known stat type **fails the context
   at startup**; it does not silently fall back to the default
3. Restart the writer; confirm the new value is reported in the `Applied retention`
   log line and that the context came up at all
4. Confirm partitions past the boundary are **gone from `pg_inherits`** and their
   tables dropped — not that their rows were deleted
5. Confirm the partition **on** the cutoff day is still attached. The boundary belongs
   to the retained side on purpose
5a. Confirm a partition exists for **every** day from the cutoff to `pre-create-days`
   ahead — lengthening the window must *create* the older days, not only stop dropping
   them (DL-149). `SELECT count(*) FROM pg_inherits WHERE inhparent = '<type>'::regclass`
   should read `retention-days + pre-create-days + 1`
6. Watch `retention_oldest_partition_age_days{type}`. In a healthy deployment it sits
   just under the configured window; climbing past it means the sweep has stopped
7. Record the value and the reason for it

### Cautions
- Shortening retention **drops partitions on the next sweep** — a catalogue operation,
  so the data is gone in milliseconds and only a per-partition restore brings it back
  *(ADR-003 D5)*. Confirm nobody depends on it first.
- Lengthening retention increases storage. Check the projection against the
  volume figures — up to 115.2M records/day across five types.
- **Late-arriving records expire early, by design.** A gateway offline for weeks
  delivers records already deep into their window. That is the accepted business
  rule, not a defect. Do not "fix" it by switching to `ts.server`. Since T-026 those
  records are skipped, metered as `records_expired_on_arrival{type}` and ACKed — and
  the guard reads the **same cutoff** as the sweep, so changing the window moves both
  together. Two separate calculations would admit a record into a partition the sweep
  had just dropped.
- **Unbounded `DELETE` is the trap at this volume**, and it is why the mechanism above
  exists. It creates table and index bloat, long transactions, and lock contention that
  slows the writer *(STA-23C, ADR-003)*. If you find yourself writing one, the design
  has been misread.
- **A retention run killed mid-detach wedges retention silently.** An interrupted
  `DETACH … CONCURRENTLY` leaves the partition *pending detach*, after which every
  later detach on that table fails — while `INSERT` and pre-creation keep working, so
  ingestion looks perfectly healthy and the only symptom is disk. Every run now begins
  with `DETACH … FINALIZE`, so this self-heals on the next cron; if you are diagnosing
  it by hand, `pg_inherits.inhdetachpending` is the flag to look at. A non-zero
  `retention_orphans_dropped{type}` means an earlier run died between the detach and
  the drop.
- Retention lives behind its own abstraction, separate from the write path.

---

## Skill 6 — Extend the API safely

The API is versioned *(STA-38)* specifically so consumers do not break.

### Non-breaking — safe in the current version
- Adding a new optional query parameter
- Adding a new field to a response
- Adding a new endpoint
- Loosening validation

*Worked example — DL-151, 21 Sep 2026:* adding `/v1/stats/{type}/{clsbox}/{from}[/{to}]`
was non-breaking (a new endpoint); **removing** the `clsbox` query parameter from
`/v1/stats/{type}` was breaking by this list and was done anyway, because nothing is
deployed against the draft and D-09 is still open. Once ZC confirms, that option is gone.
Two rules the example set: a path parameter keeps its **wire name** (`{clsbox}`, never
`{mac}` — ADR-007 §1), and an open-ended range is `ts_device >= from`, never `to = now`.

### Breaking — requires a new version
- Removing or renaming a response field
- Making an optional parameter mandatory
- Changing a field's type or format
- Changing default sort order, page size, or pagination semantics
- Tightening validation
- Changing an error status code

### For any change
- [ ] Update the OpenAPI spec in the same commit *(STA-37)*
- [ ] Confirm it is still GET-only *(STA-32)*
- [ ] Run Skill 2 (tenant isolation)
- [ ] Confirm the change applies consistently across **all** stat types

---

## Skill 8 — Instrument a failure path

Run this whenever you add or change code that can fail. The pipeline's
defining characteristic is that it fails *silently* — every failure path must
leave a trace someone can act on.

### For every failure path, confirm

- [ ] Does it log, dead-letter, or return an error? (One of the three —
      never none.)
- [ ] Does the log line include `uid`, stat type, tenant, and `clsbox`, so the
      record can be traced across all three modules? *(D1, ADR-007 §1)* — **and does it
      get them from the MDC scope, not from `addKeyValue`?** In `stats-writer` the six
      keys (`traceId`, `uid`, `statType`, `tenant`, `clsbox`, `errorCode`) are
      `WriterLogContext`'s; repeating one as a pair makes the ECS encoder throw and
      logback **drop the line** — silently after the fourth. The build fails on it
      (`WriterLogsDoNotShadowMdcKeysTest`, DL-150). In `stats-input` the scope is
      `IngestLogContext` and the same discipline applies by hand
- [ ] Does it carry `ingestSource` / the `source` label, so "external callers are
      producing all the 400s" and "Heimdal changed its envelope" stay different
      incidents? *(DL-151 — Input only)*
- [ ] Is the **full payload** kept out of `INFO` and above? These records carry
      device identifiers — IMSI, MAC, gateway IDs. *(D2)*
- [ ] Is the level right? `WARN` for handled-but-abnormal (retry, dead-letter,
      validation failure); `ERROR` only for conditions needing a human. *(D1)*
- [ ] Is there a metric as well as a log? Logs explain *why*; metrics show
      *how often*. *(STA-27)*
- [ ] Would this failure be visible on a dashboard within minutes, or only
      discovered by someone reading logs?

### The silent-failure test

For each new failure path, answer: **if this started happening 100 times an
hour tonight, how would anyone find out before tomorrow?**

If the answer is "someone would notice the data was missing," it is not
instrumented.

### Error response contract *(D3 — RFC 9457 confirmed by ZC)*
- 400 responses use RFC 9457 Problem Details, naming **every** offending field
  and reason in one response — not one round trip per failure
- `uid` echoed back so the rejection can be correlated with our logs
- Never echo the offending **value** — payloads carry device identifiers
- Unrecognised stat type gets its own code (`unknown_stat_type`), not a
  generic field error — it signals a routing problem, not a malformed record
- Same shape reused by Module 1 and Module 3, so they do not diverge
- Cross-tenant reads return **empty**, never 403 — a 403 confirms the record
  exists

---

## Skill 7 — Onboarding a new engineer

Order matters — the domain before the code.

1. **Read** `CLAUDE.md` end to end, especially §5 (rules) and §6 (traps)
2. **Read** `PROJECT_STATUS_AND_PLAN.md` for where the project actually is
3. **Read** `docs/requirements/BRD-v1.2-amendment.md` for the business workflow
4. **Skim** `docs/requirements/SRS-v1.2-amendment.md` — you will cite `STA-nn` IDs
5. **Read** `CLAUDE.md` §7 so you know what is still unsettled and what is closed
6. **Run** the local stack per `DEVELOPMENT.md`
7. **Trace one record** end to end: POST → exchange → queue → table → GET
8. **Break it deliberately:** send an invalid payload, stop the consumer, stop
   RabbitMQ (expect a 503, not a 200), stop PostgreSQL. Watch where each failure
   surfaces — and notice which ones are silent.
9. **First task:** something that exercises Skill 1 or Skill 3

### The four traps to internalise on day one
1. **`tenant` vs `maintenant`** — they are equal in most sample data and on every
   live tenant but minol. Filtering on the wrong one passes every test and leaks
   data in production — or, on minol, silently drops one row in ten (DL-146).
   Always write fixtures where they differ.
2. **Unix seconds, not milliseconds** — treating `ts.device` as millis gives 1970.
3. **Unsorted pagination is not stable** — always sort on an indexed field with a
   unique tie-breaker, not only when the caller asks.
4. **Never swallow an exception** — return an error, dead-letter, or log at
   WARN/ERROR with `uid` and stat type. Silence is the one unacceptable outcome.

### Watch for
This is a JVM/Spring Boot project. If your background is Python, the shape is
familiar even if the syntax is not:

| Concept | Python equivalent | Here |
|---|---|---|
| Dependency injection | manual wiring / FastAPI `Depends` | Spring constructor injection |
| Message consumer | `pika` callback | `@RabbitListener` |
| Repository | SQLAlchemy / psycopg wrapper | Spring Data repository interface |
| Migrations | Alembic | Flyway or Liquibase, versioned |
| Config | `.env` / Pydantic settings | `application.properties` + `@ConfigurationProperties` |
| Request validation | Pydantic model | Bean Validation annotations |
| Route | FastAPI decorator | `@GetMapping` |
