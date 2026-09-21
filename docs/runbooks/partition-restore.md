# Partition restore runbook — one day, one type, parent stays online

**T-051 · ADR-003 D5 · DL-111 (RPO ≤ 5 min, RTO 1 h per partition / 24 h full cluster) · DL-155**

## Purpose and the two numbers this procedure is measured against

Use this procedure to put back **one daily partition of one stat type** — `lorastats_2026_09_14`,
say — after it has been lost or corrupted, **without taking the parent table offline**: the
Writer keeps inserting into every other day the whole time, and the API keeps reading them.

It is timed. D5a (DL-111) signed **RTO 1 h per partition**, and a target nobody measures is
not a target, so every step below has a stopwatch column and the drill that rehearses this
runbook (`PartitionRestoreDrillIntegrationTest`) prints the same table. The measured run on
21 September 2026 — a 200,000-row day on PostgreSQL 17, a writer inserting into the
neighbouring day throughout — took **1.23 s** end to end; the ATTACH itself took **1 ms**;
the writer saw 6,517 inserts, no failures, worst case 55 ms.

**RPO ≤ 5 min** is the other half, and it is not this runbook's to deliver — it is what
makes this runbook *possible*. It needs continuous WAL archiving to be **on** and **watched**:
`archive_mode=on`, `archive_timeout=300` (a segment is shipped at least every five minutes
even if it is not full), and an alert when the archiver falls behind or fails
(`ops/prometheus/postgres-alerts.yml`). The Compose stack has both since DL-155. On a managed
PostgreSQL, confirm the equivalent with the platform owner — it is the same conversation as
encryption at rest (D-12 → platform, DL-114).

## What you are restoring from

Two sources, in order of preference:

| Source | When it exists | What you get |
|---|---|---|
| **Per-partition logical dump** — `pg_dump -t <partition> -Fc`, taken nightly for every partition older than today | Operations job, one file per partition per day. The drill's step 1 is exactly this command | The partition as of the dump: rows, and the DDL to rebuild it. **Not** the rows that arrived after the dump — for a day that is still receiving late records, see *A day that is still live* |
| **PITR** — base backup + archived WAL, restored to a *scratch* instance at the moment before the loss, then `pg_dump -t <partition>` from there | Always, if archiving is on. This is what RPO ≤ 5 min buys | The partition as of any point in time, to within `archive_timeout`. Slower: a scratch instance has to replay WAL first — that replay, not the steps below, is where the 1 h RTO is spent on a large cluster |

Either way you end up with a **`.dump` file of one partition**, and the rest of this runbook
is the same.

## Preconditions — read all four before touching anything

1. **Pause the sweep, or know that the day is inside the window.** Retention runs daily
   (`stats.retention.schedule`) and drops any partition wholly older than
   `RetentionWindow.cutoffDay(type)`. A day restored at or past the cutoff is dropped by the
   next run — the drill destroys its own evidence. Either set `stats.retention.schedule=-` on
   every Writer replica of this type for the duration, or check first:

   ```sql
   -- the day you are restoring must be >= this
   SELECT current_date - (${stats.retention.days-by-type.<type>:-${stats.retention.default-days}})::int;
   ```

   If the day is *outside* the window, stop: nothing you restore will survive, and a record
   that old is `expired_on_arrival` by policy (D5b) — the same trap as a 90-day-old redrive.

2. **The parent exists and is healthy.** `SELECT to_regclass('<type>')` is not null and
   `SELECT count(*) FROM pg_inherits WHERE inhparent = '<type>'::regclass AND inhdetachpending`
   is **zero**. A pending detach (trap 27) means a sweep was interrupted; let the next
   maintenance pass finalise it (`DETACH … FINALIZE`, DL-132) before you attach anything.

3. **The partition you are restoring is absent.** `SELECT to_regclass('<partition>')` is
   **null**. If it exists and is *corrupt*, detach and drop it first — the sweep's own
   two statements, by hand — and record that you did.

4. **You know the day's bound and it matches the name.** `lorastats_2026_09_14` means
   `FOR VALUES FROM ('2026-09-14 00:00:00+00') TO ('2026-09-15 00:00:00+00')`, UTC. Nothing
   below derives it from the file; you write it, twice, from the name.

## Procedure

Run every `psql` step with `ON_ERROR_STOP=1`. Start the clock at step 1.

| # | Step | Command | Measured 21 Sep 2026 |
|---|---|---|---|
| 1 | **Do not restore the dump as-is.** List its table of contents and drop the two attach entries | `pg_restore -l <partition>.dump > toc.txt`<br>`grep -vE 'TABLE ATTACH\|INDEX ATTACH' toc.txt > toc.staging.txt` | — |
| 2 | Restore the partition **standalone** — a plain table with the parent's columns, loaded, then indexed | `pg_restore -U stats -d statsdb --no-owner --exit-on-error -L toc.staging.txt <partition>.dump` | 0.875 s (200k rows) |
| 3 | Verify the row count against the dump's source (the nightly job logs it) and that **every row belongs to the day the name says** | `SELECT count(*) FROM <partition>;`<br>`SELECT count(*) FROM <partition> WHERE ts_device < '<day> 00:00:00+00' OR ts_device >= '<day+1> 00:00:00+00';` → must be **0** | 0.030 s |
| 4 | Add a CHECK that **matches the bound exactly**, so ATTACH can prove the bound from the constraint instead of scanning every row | `ALTER TABLE <partition> ADD CONSTRAINT restore_bound CHECK (ts_device >= '<day> 00:00:00+00'::timestamptz AND ts_device < '<day+1> 00:00:00+00'::timestamptz);` | 0.011 s |
| 5 | **Re-attach.** Takes `SHARE UPDATE EXCLUSIVE` on the parent — inserts and reads continue — and finds the child's indexes by shape, attaching them to the parent's | `ALTER TABLE <type> ATTACH PARTITION <partition> FOR VALUES FROM ('<day> 00:00:00+00') TO ('<day+1> 00:00:00+00');` | **0.001 s** |
| 6 | Drop the scaffolding | `ALTER TABLE <partition> DROP CONSTRAINT restore_bound;` | 0.001 s |
| 7 | **Post-attach checks** — all four, before you call it done | see below | — |

Stop the clock. Record the total against RTO 1 h in the incident record, with the partition
name, the row count, the dump's timestamp, and which source it came from.

### Step 7 — the four post-attach checks

```sql
-- (a) the parent sees the day again, and the count matches step 3
SELECT count(*) FROM <type> WHERE ts_device >= '<day> 00:00:00+00' AND ts_device < '<day+1> 00:00:00+00';

-- (b) it is attached, and NOT pending detach
SELECT inhdetachpending FROM pg_inherits WHERE inhrelid = '<partition>'::regclass;   -- exactly one row, f

-- (c) three valid indexes on the child, each attached to its parent index
SELECT indexrelid::regclass, indisvalid FROM pg_index WHERE indrelid = '<partition>'::regclass;  -- 3 rows, all t
SELECT count(*) FROM pg_inherits WHERE inhrelid IN (SELECT indexrelid FROM pg_index WHERE indrelid = '<partition>'::regclass);  -- 3

-- (d) no scaffolding left
SELECT conname FROM pg_constraint WHERE conrelid = '<partition>'::regclass AND contype = 'c';  -- only <type>_clsbox_canonical_hex
```

If (c) shows fewer than three, the dump predates a migration that added an index: create the
missing one on the child with the parent's definition (`\d+ <type>`), then
`ALTER INDEX <parent_index> ATTACH PARTITION <child_index>`.

Un-pause the sweep if you paused it.

## Why not just `pg_restore` the file

Because it works and looks like it failed, and it shows the day half-restored while it runs.
`pg_dump -t <partition>` writes the entries in this order: `CREATE TABLE`, **`ATTACH
PARTITION`**, `COPY`, then the child's `PRIMARY KEY` and `CREATE INDEX`. Restored as-is, the
empty child is attached to the live parent *first*; PostgreSQL creates matching child indexes
at that moment; the `COPY` then loads row by row into a partition the API can already see,
maintaining indexes as it goes; and the dump's own `ADD CONSTRAINT … PRIMARY KEY` and
`CREATE INDEX` then fail — *"multiple primary keys … are not allowed"*, *"relation … already
exists"* — so `pg_restore` reports **"errors ignored on restore: 2" and exits 1**. The rows
are correct. An operator following a runbook that says "run pg_restore" sees a non-zero exit
at 03:00 and has no way to know it is the harmless one. Dropping the two `ATTACH` entries
from the TOC gives the D5 shape instead: load and index off-line, attach in one statement,
the day appears atomically.

## Two things that make this slower than the drill

* **Attach without the CHECK.** Skip step 4 and step 5 still works — but ATTACH must then
  scan every row to prove the bound, holding its lock for the duration. On a 23-million-row
  production day that is minutes, not a millisecond. The CHECK is what the RTO depends on.
* **A day that is still live.** The nightly dump of *yesterday* misses any late record
  (STA-23B: an offline gateway delivers weeks later) that arrived after the dump. Restoring
  it loses those. For a day still inside the pre-create horizon, prefer PITR to the moment
  before the loss; for an older day, accept the dump and record the dump timestamp so the
  gap is known. `ON CONFLICT (ts_device, uid) DO NOTHING` means a Heimdal replay over the
  restored day is harmless either way (ADR-005).

## What this runbook does not cover

* **Full-cluster recovery** (24 h RTO): PITR of the whole instance from base backup + WAL.
  Platform's procedure; this runbook is the *per-partition* case D5 carved out of it.
* **A corrupt parent.** If the parent itself is damaged, detaching a healthy child does not
  help; that is the full-cluster case.
* **Rows that never reached PostgreSQL.** A record lost between Heimdal and the INSERT is
  in one of `FLOW.md` §8's fifteen windows, not in a backup — look at the DLQ and the
  counters, not here.

## The drill

`stats-writer/src/test/java/…/retention/PartitionRestoreDrillIntegrationTest.java` runs every
command above against the real PostgreSQL 17 container with a writer inserting into the
neighbouring day throughout, asserts each of the step-7 checks, asserts the writer saw no
failure and no insert slower than a second, prints the timing table, and fails if the total
exceeds one hour. It runs in every full build: the runbook cannot drift from what PostgreSQL
actually does without a red build saying so.
