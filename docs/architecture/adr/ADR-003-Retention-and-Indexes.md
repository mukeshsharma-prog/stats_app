# ADR-003 — PostgreSQL Retention, Partitioning, and Index Management

**Status:** `APPROVED`
**Decision owner:** Data platform / Architecture Review Board
**Requirements:** STA-21, STA-21B, STA-21C, STA-23, STA-23A/B/C, STA-24, STA-26, STA-34 through STA-36
**Supersedes:** the `PRIMARY KEY (uid)` shape in `docs/decisions/POSTGRESQL_BASELINE.md` §2
**Amended 29 August 2026:** `pg_partman` is no longer a conditional dependency (D1);
clock skew is decided (D9); the late-arrival policy carries engineering agreement but
still awaits product sign-off.
**Amended 4 September 2026 (DL-111, DL-112):** **both rows that were open inside this
approved ADR are now SIGNED.** D5a — RPO ≤ 5 min via continuous WAL archiving; RTO 1 h
for a single partition, 24 h for a full cluster restore. D5b — the late-arrival policy
as agreed, skip / meter / ACK / alert. **T-051 and T-026 are unblocked**, and Q15b
(DL-106) confirms `ts.device` is genuine device time, so the `ts_device` basis both
decisions rest on is measurement time rather than arrival time.
**Amended 3 September 2026 (D14b, DL-095):** every typed stat-type table carries a
nullable `data_extra jsonb`. D2 chose typed columns for the four stable types, which
leaves an unknown `data` member — explicitly valid under D14, since the payload schemas
are `additionalProperties: true` — with nowhere to go, so the Writer dropped it
silently. That is the same failure D2 rejected in writing when it chose a `jsonb`
interface map for `netstats`. The column is NULL whenever the payload is exactly the
schema, so it costs a null-bitmap bit rather than the per-row key names D2 exists to
avoid, and unmapped members are metered as `records_unmapped_data_field{type}`.
**Amended 29 August 2026 (ADR-007):** column names follow the production wire
format. `gateway` is now **`clsbox`** and `ts_processor` is now **`ts_server`**,
here and everywhere else. Three envelope members that production sends and this ADR
never modelled — `version`, `status`, and the unread `ts.datastore`/`ts.converter`/
`ts.expiresOn` — are addressed in the DDL below. Clock skew (D9) compares
`ts.device` against **`ts.server`**. Retention still keys on `ts_device`, unchanged.

## Context

PostgreSQL is the only V1 database and the version is fixed at **PostgreSQL 17**.
The business answer selects `ts.device` as the retention basis and identifies
tenant, clsbox, and `ts.device` as the dominant query pattern. At 160,000
gateways reporting every 10 minutes, the average workload is 23.04 million
records per type per day, or 115.2 million/day across five types, before
synchronised reporting bursts are considered.

Two figures drive every decision below:

| | |
|---|---|
| Live rows per type at 90 days | 23.04M × 90 ≈ **2.07 billion** |
| Live rows across five types | ≈ **10.4 billion** |
| Sustained deletion rate required | **equal to the ingestion rate, permanently** — 267 rows/sec/type |

Retention is not a periodic cleanup at this volume. It is a second write path
running continuously at full ingestion rate. A row-by-row deletion job would
generate roughly 115 million dead tuples per day across five types, on tables of
around two billion rows each, which autovacuum will not keep up with — producing
table and index bloat, lengthening deletes over time, and degrading the writer.

## Decisions

### D1 — Partition key and granularity

**Decision:** every stat-type table is `PARTITION BY RANGE (ts_device)` with
**daily** partitions. Retention is implemented as `DETACH PARTITION CONCURRENTLY`
followed by `DROP TABLE`, not as row deletion.

Rationale: dropping a partition is a catalog operation — milliseconds, no dead
tuples, no vacuum debt, no index bloat. Daily granularity gives 90 live
partitions per type and 450 across five types, comfortably within what the PG17
planner handles. Hourly would give 10,800 and buys nothing.

The retention basis and the dominant query pattern are both `ts_device`, so one
partition key serves both: retention becomes a drop, and time-ranged queries
prune partitions. That alignment is fortunate and should not be given up lightly.

Partitions must be **pre-created ahead of ingestion**, or inserts fail.

**Decided 29 August 2026: pre-creation is a scheduled task inside `stats-writer`,
creating `pre-create-days` ahead, guarded by the same `pg_try_advisory_lock` that D3
already requires for retention. `pg_partman` is never a dependency.** If a managed
instance happens to offer the extension, that is a convenience, not a design input.

Three reasons this is not worth leaving conditional. Managed PostgreSQL frequently
does not expose the extension, so building on it imports a platform risk for no gain.
The advisory lock is needed regardless — D8 deploys N writer replicas per stat type,
and N replicas racing to create tomorrow's partition is exactly the failure the lock
exists to prevent; once the lock is there, the scheduled task is a few lines on top of
it. And leaving the choice open was the last thing holding task T-012 in a flagged
state for work that is otherwise ready.

**The consequence to accept:** we own the pre-creation logic and its failure mode. A
test that removes pre-creation must prove the failure is **loud** — a silent failure
here stops ingestion for a whole stat type.

### D2 — Typed columns versus `jsonb`

**Decision:** **typed columns** for `lorastats`, `ltestats`, `sysstats`, and
`omsstats`. **Hybrid** for `netstats` — typed columns for the stable fields,
`jsonb` for the interface map.

Rationale: `jsonb` stores every key name in every row. `lorastats` has 13 keys
averaging ~14 characters, roughly 180 bytes per row of nothing but repeated key
strings — on the order of **375 GB per type** of pure overhead at steady state.
Typed columns eliminate it, are directly indexable, and keep the dominant filters
off JSON path scans (STA-21C).

`netstats` is the exception on purpose, and the reason survives Q4a being
answered. `docs/schemas/netstats-v1.schema.json` is now strict — `required` is
`[timespan, eth0, wwan]`, and the interface shape is fully specified — but it is
still deliberately **open at both levels**:

* `patternProperties` plus `additionalProperties: true` at the `data` level — any
  interface name is legal, and Q4a explicitly confirmed that (`eth1`, `ppp0`).
* `additionalProperties: true` at the interface level — an unknown field on an
  interface is accepted and preserved.
* `tx` and `rx` are digit-only `string` on the wire, converted once on write.

That openness is the whole argument. Fixed `eth0_*` / `wwan_*` columns would
**silently drop** an interface the schema permits — directly violating the
unknown-field policy of accepting and preserving unknown `data` members
(`claude/CLAUDE.md` §7, decision D14). A `jsonb` interface map
preserves them by construction, and no migration against two billion rows is
needed the first time a gateway reports `eth1`.

Therefore: `data.timespan` becomes a typed column, since it is the one scalar the
schema commits to. `data.eth0`, `data.wwan`, and any future interface stay in
`jsonb`. **This is settled, not pending** — revisit only if measurement shows the
`jsonb` path failing the dominant query pattern.

**Counter storage.** `tx`/`rx` become `BIGINT`. The schema's digit pattern caps at
19 digits, which is wider than the signed `BIGINT` maximum, and the gap is not
expressible as a regex — so **the Writer must range-check before conversion**
(STA-04C). An unchecked overflow corrupts the value with no error anywhere.

Use `bigint`, not `integer`, for all counters. The SRS says "integer", but these
are monotonic counters over long device uptimes and `int4` overflow is a silent
data corruption. `bigint` costs four bytes.

### D3 — Retention job / partition owner

**Decision:** `stats-writer` owns partition creation and retention, guarded by a
PostgreSQL **advisory lock** per stat type.

Rationale: the writer is the only component permitted to write (claude/CLAUDE.md rule
2); giving retention to anything else creates a second writer. But horizontal
scalability of the writer is a MUST (STA-06/26/39), so with three replicas you
would otherwise get three concurrent retention jobs racing on the same
partitions. `pg_try_advisory_lock` is the boring solution and adds no dependency,
where ShedLock or Quartz would.

```sql
SELECT pg_try_advisory_lock(hashtext('stats.retention:' || :statType));
```

If the lock is not acquired, the replica skips that run and logs at DEBUG. Stagger
the five types rather than firing them together.

Schema changes remain versioned migrations, owned by the writer module and
reviewed like code.

### D4 — Batch size, schedule, and transaction limit

**Decision:** on the partition path, retention runs **once daily off-peak** and
drops one partition per type. Batch size does not apply.

The batched-deletion properties remain defined for the **fallback path only**, in
case partitioning is not approved for the first increment:

```properties
# Fallback path only — not used when partitioning is active
stats.retention.delete-batch-size=10000
stats.retention.fallback-schedule=0 */5 * * * *
stats.retention.max-batches-per-run=50
stats.retention.max-run-duration=PT2M
```

Rationale for the fallback numbers: 10,000 rows per `DELETE` keeps the
transaction under a second and bounds lock duration and WAL per transaction;
below ~1,000 the per-statement overhead dominates, above ~50,000 the locks start
interfering with the writer. At 288 runs/day, eight batches per run is exactly
break-even against ingestion, so `max-batches-per-run=50` provides roughly six
times headroom to drain a backlog after downtime. A run must loop until it
deletes fewer than `batch_size` or hits the time budget — one batch per run
cannot keep up and the table grows without bound.

All values are starting points to be confirmed by the Phase 5 load test against
measured lock duration and autovacuum lag.


### D7 — Envelope columns carried over from the ARD (24 August 2026)

Two ARD column proposals were recorded as adopted without landing anywhere. Split:

**`ingested_at` — rejected as a rename.** It is the same value as `received_at`,
which is already in the table above and in the built configuration. Renaming a
column buys nothing and costs a migration; the ARD name is noted here so the next
reader does not think a column is missing.

**`schema_version smallint NOT NULL` — adopted.** It records which version of our
own JSON Schema validated the record, not anything the wire carries.

Rationale: adding a column to a table of roughly two billion rows is a genuinely
expensive operation, and the moment a schema changes — which STA-14A guarantees
will happen, since adding a stat type is routine — the first question of any
backfill is *which rows were written under which rules*. Without this column that
question is answered by joining against deployment dates, which is to say it is
not answered. Two bytes now against a rewrite later is not a close call.

It is `NOT NULL` with no default: the Writer sets it from the schema that actually
validated the record, so a row can never claim a version nothing verified.


### D8 — Writer deployment shape: one image, one deployment per stat type

**Decision:** `stats-writer` stays a **single Docker image** (Q2 — three images
total, unchanged). It is **deployed once per stat type**, with `STAT_TYPE`
selecting which queue that instance consumes.

This reconciles the ARD's per-type deployment proposal with what is actually
built, and the two were never really in conflict — the ARD is describing a
deployment topology, not a fourth image.

Why per-type deployment rather than one instance consuming all five: per-type
consumer concurrency, prefetch and replica count are a MUST (STA-11A, STA-20A,
STA-26A), and they are only independently tunable if the instances are separate.
It also means a crash loop caused by one type's payload cannot take down
consumption of the other four — the same isolation argument that produced the
per-type queues in the first place, carried one layer up.

```properties
# Which stat type this instance consumes. `all` is a DEVELOPMENT convenience so a
# single container serves the local stack; production sets exactly one type.
stats.writer.stat-type=${STAT_TYPE:all}
```

**Retention is unaffected.** It stays with the writer behind the per-type advisory
lock of D3, which is needed regardless because each per-type deployment scales to
several replicas. The ARD's separate sixth invocation for partition maintenance is
**rejected** — see ADR-002 decision 10.

### D5 — Backup and restore strategy

**Decision:** restore is **per partition**, not whole-table. A daily
`RANGE (ts_device)` partition is an independently restorable unit, so recovering a
given day's statistics never requires restoring a two-billion-row table. Backup
tooling and verification must therefore operate at partition granularity, and a
restore drill must prove a single partition can be recovered and re-attached
without taking the parent table offline.

Rationale: at multi-terabyte scale a whole-table restore is measured in hours and
is almost never what an incident actually needs. Real failures are bounded in
time — a bad day's ingest, a wrongly dropped partition, a corrupted range — and
the partition boundary already matches that shape. This falls out of D1 at no
additional cost, which is why it is decided here rather than escalated.

**Still open — RPO and RTO targets.** The restore *mechanism* above is
engineering's to choose and is settled. The *targets* are not: BR-30 and BR-35 in
the BRD flag them as needing business sign-off, and engineering should not default
them. They are tracked as their own row in the register below so that this
decision does not bury them.

Framing to take to that conversation: this is 90-day-retention telemetry, not
financial records. At multi-terabyte scale a full restore is measured in hours,
so a tight RTO is expensive and may not be achievable at all. The useful question
is **"if we lost the last 15 minutes of statistics, or needed 8 hours to restore,
what actually breaks?"** The answer may be "very little", which materially
changes the cost. Ask it before a 99.99% target is specified reflexively.

### D6 — Migration tool

**Decision:** **Flyway.** No longer provisional — Q1a was answered on 18 August
2026 and there is no internal reference service to mandate otherwise.

Rationale: Spring Boot auto-configures it, and plain-SQL migrations suit
partition DDL and `pg_partman` setup far better than Liquibase's XML
abstraction, which fights you on anything PostgreSQL-specific.

### D9 — Clock skew: a future `ts.device` is rejected at Input (29 August 2026)

**Decision:** reject at `stats-input` when `ts.device > ts.server + skew tolerance`.
Accept anything inside the tolerance. The response is **400** with a `ts.device`
field-level error carrying the reason code `clock_skew_exceeded`, and the rejection is
metered as `records_clock_skew_rejected{type}`.

```properties
# stats-input. Starting value — externalised like every other threshold (G4 precedent).
stats.input.validation.max-clock-skew=PT5M
```

This closes **Q14a**, which was filed as a question for product and never was one.

Rationale. Partition pre-creation is finite by definition (D1). A gateway with a 2038
clock either demands unbounded partition creation or fails its insert deep inside the
Writer, where the caller can no longer be told anything — and the record then occupies
the DLQ for a fault the sender could have fixed. Rejecting at the edge is deterministic,
testable, and hands Heimdal a 4xx it can act on, consistent with STA-03A. The tolerance
exists because a few seconds of NTP drift is normal and must not be an outage; five
minutes is a starting value to be tuned against measurement, never hard-coded.

**Why this is not a schema rule.** The check compares two instance locations
(`ts.device` against `ts.server`), which JSON Schema 2020-12 cannot express — there
is no `$data` in the draft. So it is a code-level check in Input, and the five
`edge/<type>-clock-skew-future.json` fixtures stay **schema-valid**. They now carry an
explicit `$expect: "input-reject"` marker with an `$expectCode`, asserted by
`docs/schemas/validate-fixtures.py` — which is why the CI gate reports **91 assertions**
where the Java parity test expands to **84 schema cases** (both figures as at 8 September
2026; the gap, not the numbers, is the point). That gap is deliberate: the
Java test mirrors the schema oracle only, and the marker is not a schema case. The
Input-side 400 is asserted by T-013. The point is that the expectation is now **stated**
rather than inherited from a directory convention.
Before this decision both oracles treated every `edge/` fixture as expected-valid, which
meant the open question already had "accept" baked into ten green assertions — see trap
16 in the tracker.

## Consequence: `uid` uniqueness cannot be global

**This changed STA-21B and STA-25A. Both are amended (24 Aug 2026) — see the
sign-off note at the end of this section.**

PostgreSQL requires that any unique index on a partitioned table include the
partition key. There are no global unique indexes across partitions, in PG17 or
any current version. The shape proposed in `POSTGRESQL_BASELINE.md` and in the
earlier draft of this ADR is therefore **not creatable**:

```sql
PRIMARY KEY (uid)                                  -- impossible when partitioned by ts_device
CREATE UNIQUE INDEX uq_<type>_uid ON <type> (uid); -- impossible
```

It becomes:

```sql
PRIMARY KEY (ts_device, uid)
```

which enforces uniqueness **within a partition**, not globally.

**Why this is acceptable.** The published message is byte-identical to the
received record (D6 of the answer set), so a redelivered record carries the same
`ts_device`, lands in the same partition, and hits the same constraint. Duplicate
suppression still works for the case it exists to defend against —
broker redelivery — and the idempotent write remains:

```sql
INSERT INTO <type> (...) VALUES (...) ON CONFLICT (ts_device, uid) DO NOTHING;
```

The same holds for replay after a Heimdal outage (Q13a): a replayed record is a
duplicate by definition and carries its original `ts_device`, so it conflicts and
is absorbed.

**`DO NOTHING` must not be silent.** The statement discards a row and reports
nothing, which conflicts with the no-silent-error-handling rule in
`claude/CLAUDE.md` §9. The writer MUST read the affected-row count and, when it is
zero, increment `records_duplicate_suppressed{type}` — the `duplicate` metric
STA-27A already requires. A steady low rate is redelivery working; a step change
is a signal, and without the counter it is invisible.

That counter also covers the one residual correctness risk in the narrowed key.
The key does **not** include `tenant`, so if two tenants ever carried the same
`uid` at the same `ts_device`, the second record would be suppressed — silent
cross-tenant data loss, and a naive test would not see it because fixtures rarely
reuse a `uid` across tenants. Widening the key to `(ts_device, tenant, uid)` would
prevent it outright and would still catch every redelivery, since a redelivered
record carries the same tenant too — but it costs roughly 100–200 GB across 10.4
billion rows for a case ZC states cannot occur. **Decision: meter first, widen
only if the counter fires or ZC declines to guarantee `uid` uniqueness across
tenants.**

**What is genuinely lost.** A different record reusing a `uid` in another
partition would not be caught. Given ZC's answer that `uid` is unique and Heimdal
does not deliver duplicates, this is an acceptable downgrade of a defence-in-depth
measure — but it is a conscious trade, recorded here, not an accident.

**Second consequence: `uid`-only lookup cannot prune.** `GET /v1/stats/{type}/{uid}`
(`docs/contracts/openapi.yaml`) supplies no time bound, and neither the primary key
nor the covering index `(tenant, clsbox, ts_device DESC, uid DESC)` helps, because
both lead on a column the caller did not give. The query therefore touches all 90
live partitions. A non-unique secondary index on the parent turns that from 90
sequential scans into 90 index probes:

```sql
CREATE INDEX idx_<type>_tenant_uid ON <type> (tenant, uid);
```

`tenant` leads because the tenant predicate is mandatory on every query (rule 3).
A few milliseconds for a non-dominant lookup is acceptable. If the Phase 5
measurement says otherwise, the structural fix is an optional `from`/`to` hint on
that endpoint — cheap now while the OpenAPI is still a draft pending ZC
confirmation, breaking later.

**SRS amendment — done.** STA-21B and STA-25A both read "`uid` … with a unique
constraint", which describes an object PostgreSQL cannot create on a partitioned
table. Both are amended in `docs/requirements/SRS-v1.2-amendment.md` and cite this
ADR. The amendment records what the database permits; it is not a choice between
options, which is why it is ratified here rather than escalated. Product is
informed, not asked.

## Consequence: late-arriving records need a policy

Retention keys on `ts.device`, so a record can arrive already past its window.
Partitioning changes what happens to it. Under batched deletion it would be
inserted and removed on the next sweep; under partitioning its target partition
may no longer exist, and the insert fails into the DLQ.

| Option | Behaviour | Assessment |
|---|---|---|
| A — `DEFAULT` partition | Strays land in a catch-all the job clears separately | Works, but a `DEFAULT` partition degrades pruning and can grow unnoticed |
| **B — reject at the writer** | Writer checks record age; past retention, it skips the write, increments `records_expired_on_arrival`, and ACKs | **Recommended.** Writing a row you will delete minutes later is pure cost |
| C — clamp to oldest live partition | Record stored with a falsified partition placement | Rejected — corrupts the time series |

**Option B needs product sign-off**, because it is a deliberate
data-loss-by-design path, even though it only discards data that retention would
remove almost immediately anyway. It must be metered and logged, never silent.

**Engineering agreed Option B on 29 August 2026 (Naman Vashishtha), with one
addition: alert when `records_expired_on_arrival` is non-zero.** A systemic gateway
backlog and a harmless one-off look identical in the raw count; only the alert
distinguishes them. Engineering agreement is *not* the signature — and **that
signature was given on 4 September 2026 (D5b → DL-112). T-026 is unblocked.** It was
held until then because Q15b left open the possibility that `ts.device` was stamped on
arrival, in which case no record could ever be late and this policy governed a path
production never takes; DL-106 answers that. The alternative to Option B
is writing rows into a partition that does not exist, so there is no third path to wait
for.

## Required table and index direction

```sql
CREATE TABLE lorastats (
    uid            text        NOT NULL,
    tenant         text        NOT NULL,
    maintenant     text        NOT NULL,
    -- Stored in ONE canonical case. The wire admits either (the envelope schema is
    -- ^[0-9A-Fa-f]{12}$ and stays that way, so a lowercase record is never rejected),
    -- but the Writer uppercases before the INSERT and this CHECK enforces it. Without
    -- it one device becomes two keys in the covering index below and the API's
    -- case-insensitive clsbox filter returns a silently partial page (DL-115).
    clsbox         text        NOT NULL
                   CONSTRAINT lorastats_clsbox_canonical_hex
                   CHECK (clsbox ~ '^[0-9A-F]{12}$'),
    ts_server      timestamptz NOT NULL,
    ts_device      timestamptz NOT NULL,
    received_at    timestamptz NOT NULL DEFAULT now(),
    -- Heimdal's record version, stored as received. NOT a payload discriminator:
    -- all three incompatible lorastats dialects carry version 1 (ADR-007 §4).
    -- Never branch on this column.
    record_version smallint,
    -- Which of OUR schema versions validated this row. See D7: adding a column to
    -- two billion rows later is the expensive path, and the first question of any
    -- backfill is which rows were written under which rules.
    schema_version smallint    NOT NULL,
    -- Envelope members production sends that we preserve and never read
    -- (ADR-007 §2). Held as one jsonb rather than three columns because nothing
    -- queries them and ADR-004 requires the record to survive a round trip intact.
    -- Holds ts.datastore, ts.converter, ts.expiresOn and status.
    passthrough    jsonb,
    -- Unknown `data` members, preserved verbatim (D14b, DL-095). NULL whenever the
    -- payload is exactly the schema, which is almost every row. Not indexed.
    data_extra     jsonb,
    -- typed payload columns, bigint for counters
    rx_nr_pkts     bigint      NOT NULL,
    rx_crc_missing bigint      NOT NULL,
    -- … remaining lorastats counters
    PRIMARY KEY (ts_device, uid)
) PARTITION BY RANGE (ts_device);

-- Created on the parent; PG propagates to every partition
CREATE INDEX idx_lorastats_tenant_clsbox_device_uid
    ON lorastats (tenant, clsbox, ts_device DESC, uid DESC);

-- Supports GET /v1/stats/{type}/{uid}, which supplies no time bound and so
-- cannot prune partitions. Index probe per partition instead of a seq scan.
CREATE INDEX idx_lorastats_tenant_uid ON lorastats (tenant, uid);

-- One per day, pre-created ahead of ingestion
CREATE TABLE lorastats_2026_08_16 PARTITION OF lorastats
    FOR VALUES FROM ('2026-08-16') TO ('2026-08-17');
```

**Every stat-type table carries the `clsbox` CHECK and `data_extra`.** Both are
invariants of the shape, not of one writer: omitting the CHECK reopens the split
index key (DL-115) and omitting `data_extra` reopens the silent drop (D14b). Neither
failure raises an error, so neither is caught by the type's own tests.

`record_version`, `passthrough` and `data_extra` are nullable on purpose: every fixture written
before 29 Aug 2026 omits them, and a `NOT NULL` here would make the pre-ADR-007
corpus unwritable for no benefit. Neither is indexed — nothing queries them.

**A note on typed columns and the wire types.** ADR-007 §6 confirmed that the same
concept is encoded differently per stat type: `timespan` is an integer in
`lorastats`, `omsstats` and `netstats` but the string `"300"` in `sysstats`, and
every `ltestats` numeric is a string on the wire. The typed-column design is
unaffected — the Writer parses to the column type either way — but the parse is
now **per type**, not one shared coercion, and it must range-check before
conversion exactly as the `netstats` counters already require.

The `retention_at` column from the baseline draft is **dropped** on the partition
path — the partition boundary is the retention mechanism, and a separate column
would be a second source of truth. It returns only if the fallback path is used.

Retention execution:

```sql
ALTER TABLE lorastats DETACH PARTITION lorastats_2026_05_18 CONCURRENTLY;
DROP TABLE lorastats_2026_05_18;
```

`DETACH ... CONCURRENTLY` avoids the long `ACCESS EXCLUSIVE` lock that a plain
detach would take against the writer.

## Decisions register

| Decision | Resolution | Status |
|---|---|---|
| Exact PostgreSQL version/managed service | PostgreSQL 17 | **DECIDED** |
| Partition key and granularity | `RANGE (ts_device)`, daily; detach + drop | **DECIDED (D1)** |
| Typed columns versus `jsonb` | Typed for four types; hybrid for `netstats` | **DECIDED (D2)** |
| Retention job/partition owner | `stats-writer` + advisory lock per type | **DECIDED (D3)** |
| Batch size, schedule, transaction limit | Daily partition drop; batch values retained for fallback only | **DECIDED (D4)** |
| Backup and restore strategy | Per-partition restore, not whole-table; restore drill at partition granularity | **DECIDED (D5)** |
| RPO / RTO targets | Needs a number from product + operations (BR-30, BR-35) | **ESCALATED** |
| Migration tool | Flyway | **DECIDED (D6)** — provisional wording removed once Q1a closed, 18 Aug 2026 |
| `uid` uniqueness scope | `(ts_device, uid)`, per partition | **DECIDED (G2 fixes column order) — STA-21B and STA-25A amended** |
| Duplicate suppression visibility | `ON CONFLICT DO NOTHING` meters `records_duplicate_suppressed`; `tenant` stays out of the key until the counter or ZC says otherwise | **DECIDED** |
| `uid`-only lookup path | Secondary index `(tenant, uid)` on the parent; revisit a time hint on the endpoint if Phase 5 says so | **DECIDED** |
| Envelope columns from the ARD | `ingested_at` rejected as a rename of `received_at`; `schema_version smallint NOT NULL` adopted | **DECIDED (D7), 24 Aug 2026** |
| Writer deployment shape | One image, one deployment per stat type via `STAT_TYPE`; retention stays behind the D3 advisory lock | **DECIDED (D8), 24 Aug 2026** |
| Late-arrival policy | Option B, reject and meter, plus an alert when the counter is non-zero | **SIGNED 4 Sep 2026 (D5b → DL-112). T-026 unblocked** |
| Partition pre-creation mechanism | Scheduled task in `stats-writer` behind the D3 advisory lock; `pg_partman` never a dependency | **DECIDED (D1), 29 Aug 2026** |
| Clock skew — a `ts.device` ahead of `ts.server` | Rejected at Input beyond a configurable tolerance (default 5 min); 400 `clock_skew_exceeded`, metered | **DECIDED (D9), 29 Aug 2026 — closes Q14a** |

## Consequences and tests

Retention tests must prove that records just inside the boundary remain and
records outside it are removed by the actual mechanism — a dropped partition, not
a simulated delete. Specifically required:

* Boundary tests either side of the retention window, keyed on `ts_device`.
* A late-arriving record with an old `ts_device` behaves per the approved
  late-arrival policy, and is metered rather than silently dropped.
* A record whose `ts_device` exceeds `ts_server` by more than the configured
  tolerance is rejected at Input with 400 `clock_skew_exceeded` and never reaches a
  partition (D9); one just inside the tolerance is accepted.
* Partition pre-creation runs far enough ahead that no insert ever fails for a
  missing partition; a test that removes pre-creation proves the failure is loud.
* Duplicate `uid` redelivery is an idempotent no-op via
  `ON CONFLICT (ts_device, uid)` **and increments
  `records_duplicate_suppressed`** — a suppressed write that meters nothing is a
  test failure, not a pass.
* Two records sharing a `uid` and `ts_device` across two different tenants: assert
  the current behaviour explicitly, so that widening the key later is a visible
  change rather than a silent one.
* `GET /v1/stats/{type}/{uid}` returns the record for a `uid` in the oldest live
  partition, and returns 404 — indistinguishable from not-found — for a `uid`
  belonging to another tenant (STA-35B).
* A restore drill recovers a single partition and re-attaches it without taking
  the parent table offline (D5).
* Concurrent writer replicas do not run retention simultaneously — advisory lock
  contention test.
* `DETACH CONCURRENTLY` does not block writes for a measurable period.

Load tests must measure write throughput at 267 rows/sec/type and at the
unconfirmed burst rate (Q13a), query latency with partition pruning, partition
drop lag, vacuum behaviour, table and index growth, and recovery after a database
outage.
