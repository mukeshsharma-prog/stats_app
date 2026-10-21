# Diagnose a stuck or growing queue

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
10. **Is a failure hand-off going unconfirmed?** `records_handoff_unconfirmed{type}`
    moving (alert `StatsAppFailureHandoffUnconfirmed`) means the retry or DLQ publish for
    that type is not being confirmed — a deleted DLQ, a broker resource alarm. Since
    DL-168 each such delivery is nacked with requeue after a 5 s pause, so the type's
    consumer slows by 5 s per failing record and good records slow with it. Fix the
    failure arm (redeclare the queue, clear the alarm); the requeued records drain on
    their own. Before DL-168 the same fault left the delivery unacknowledged and
    silently ate one prefetch slot per record until the consumer stopped.

### After resolution
- [ ] Redrive DLQ messages once the root cause is fixed — an authorised operations
      role only, explicit, traceable, scoped per type, and rate-limited to 2× nominal
      (2,667/s). Blind automatic replay is not permitted. *(ADR-002 §12)*
      **The command exists since T-052 (DL-138):** `./gradlew :stats-writer:redriveDlq`
      with `--type`, `--max-messages`, `--rate-per-second`, `--retention-days`,
      `--change-id` and `--root-cause-resolved=true`, under dedicated per-type broker
      credentials. It **skips** a record past the window (or of the wrong type, without
      `ts.device`, or not JSON) — reported as `record_skipped` and returned to the DLQ in
      place at the end of the run, so one old record at the head no longer blocks the type
      (DL-168) — confirms the copy before ACKing
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
