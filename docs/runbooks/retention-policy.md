# Add or change a retention policy

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
   just under the configured window; climbing past it means the sweep has stopped.
   **It is `NaN` until the first sweep** (DL-156) — a `NaN` a day after deployment means
   the sweep has never run, and `StatsAppRetentionSweepMissing` will say so; check
   `retention_last_sweep_timestamp_seconds{type}` (`max by (type)`) and `pg_locks` for an
   advisory lock held by a session that is not running maintenance
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
