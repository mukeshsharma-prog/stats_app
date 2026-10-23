# Replay drill

**Duplicate suppression is metered and never alerted · 2,667/s is the agreed (and
unenforceable) replay cap · 4,000 req/s is the per-instance ingest limit**

## What is being rehearsed, and what cannot be

After an outage Heimdal replays what it could not deliver. It agreed to do so at no more
than **2× nominal — 2,667/s** — on top of the ~1,333/s steady state, which holds
total ingest at the shipped per-instance limit of 4,000/s. **We cannot enforce that cap**:
the agreement is a commitment, not a control. This drill therefore proves *our*
side of it and nothing about Heimdal's:

* every replayed record is a duplicate by definition — same `uid`, same `ts.device` — and
  `ON CONFLICT (ts_device, uid) DO NOTHING` absorbs it, metered as
  `records_duplicate_suppressed{type}`;
* the counter spikes **by design and nothing alerts on it** — no rule in `ops/prometheus`
  reads it, and this drill asserts that no alert that was not already active fires;
* nothing dead-letters, nothing fails to write, the DLQ does not move;
* a 503 during a replay would be the confirm path saying no — the backpressure channel
  working, not an incident — and a 429 would be the limiter clipping at
  its own cap; both are Heimdal's to retry.

**The replay must be the same bytes.** A replayed record that had its `uid` or `ts.*`
re-stamped would be a *new* record to the Writer and would measure nothing. So the
generator gains a record/replay mode: `--save-bodies` keeps every body a run sent, byte for
byte; `--replay` sends them again, cyclically, with no re-stamping.

## How to run

```bash
ops/drills/replay.sh [http://host:port/internal/stats]
# shape: LIVE_RATE=1333 REPLAY_RATE=2667 HISTORY_SECS=30 REPLAY_SECS=60 (override in the environment)
# needs: Input, the Writer's /actuator/prometheus on :8082; Prometheus on :9090 for assertion D
```

Three phases, one type:

1. **History** — `LIVE_RATE` for `HISTORY_SECS`, bodies saved; wait for the queue to drain
   so every record is a row before anything replays it.
2. **Replay on top of live** — `REPLAY_RATE` of the saved bodies (cycled — a replay of a
   replay is still a duplicate) **and** `LIVE_RATE` of new unique records, concurrently,
   for `REPLAY_SECS`. Queue depth and oldest-message age are sampled throughout.
3. **Drain, then assert** from the Writer's own counters:

| | Assertion | Why this one |
|---|---|---|
| **A** | `records_duplicate_suppressed` delta **==** replayed 200s | every accepted replay was absorbed — not stored twice, not rejected |
| **B** | `records_persisted` delta **==** history 200s + live 200s | every *new* record landed exactly once; the replay did not cost a live record |
| **C** | `records_dead_lettered` +0, `records_write_failed` +0, `dlq_depth` unchanged | a duplicate is never a failure |
| **D** | no alert active after the drain that was not active before | the spike is designed; if a rule reads it, this is where it shows |

Records carry unique `uid`s and fresh timestamps: run against a database you are willing
to fill (≈ `LIVE_RATE × (HISTORY_SECS + REPLAY_SECS)` rows).

## First measurement — 22 September 2026

One Input, one Writer, RabbitMQ, PostgreSQL, Prometheus and Grafana on the Compose stack,
both generators on the same laptop. Absolutes are conservative; the shape is the finding.

| Phase | Offered | Achieved | 200 | 429 | 503 | Confirm p50 / p99 (server, both streams) |
|---|---|---|---|---|---|---|
| History | 1,333/s × 30 s | 1,333/s | 39,990 | 0 | 0 | 1.2 / 6.0 ms |
| **Replay** | **2,667/s** × 60 s | 2,667/s | **160,020** | 0 | 0 | 2.1 / **36.5 ms** |
| Live (concurrent) | 1,333/s × 60 s | 1,333/s | 79,980 | 0 | 0 | (same histogram) |

* **A** `records_duplicate_suppressed` +160,020 — exactly the replayed 200s. **PASS**
* **B** `records_persisted` +119,970 — exactly 39,990 + 79,980. **PASS**
* **C** dead-lettered +0, write failed +0, `dlq_depth` 6 → 6 (pre-existing drill residue).
  **PASS**
* **D** the six alerts active before (the DLQ residue's) were the six active after; nothing
  new. **PASS**
* **The queue**: `queue_depth` peaked at **85,555**, oldest-message age at **29 s** —
  against `StatsAppMainQueueAging`'s 300 s for 5 min — and it drained within **40 s** of
  the replay ending. The single Writer consumed at a peak one-minute rate of **3,137/s**
  on one type (2,092/s of it duplicates, 1,046/s inserts), above the ~2,000/s per type
  the sizing assumed; a duplicate costs one round trip to a suppressed `INSERT`,
  not nothing.
* **4,000/s in total is the shipped limit, and the limiter did not clip.** 240,000
  requests at exactly `max-requests-per-second`, zero 429s: the token bucket holds one
  second of burst and refills continuously, so a steady offered rate *at* the cap passes.
  Above it (burst-load run B) it clips immediately. Nothing to tune; recorded so the next
  reading of "4,000/s and no 429s" is not mistaken for a broken limiter.

`replay.sh` writes its results and the replayed-body corpus to `/tmp/replay-<timestamp>/`
(not committed — about 70 MB of bodies per run).

## What a real replay will look like on the dashboards

* `records_duplicate_suppressed` on the Writer dashboard's *Consumed vs persisted* panel
  rises to the replay rate while *persisted* stays at the live rate. **That gap is the
  replay, and it is healthy.** Do not alert on the duplicate counter — the
  drill is the proof that it spikes without meaning anything is wrong.
* Queue depth and oldest-message age rise for as long as the replay outpaces the Writer
  and fall back when it ends. The number to watch is the **age**, never the depth:
  the 300 s warning is what says the Writer is not keeping up, and a 6-hour outage's
  28.8M records at 2,667/s is ~3 h of replay — long enough for that rule to mean it.
* A 503 is the confirm path refusing, a 429 the limiter — both are Heimdal's to retry and
  both are the mechanism working. A **DLQ** that moves during a replay is the thing that
  is *not* supposed to happen; that is assertion C.

## The trap the drill cannot see

A message that sat long enough before being replayed is `expired_on_arrival` the moment it
returns, because its `ts.device` has aged past the retention window. The drill
replays records minutes old; a replay after a multi-week outage replays records that the
guard skips, meters and ACKs — correctly, and by design — and `StatsAppLateArrival`
fires on the hourly increase. That alert firing during such a replay is the policy
working, not a defect; the runbook for it is `docs/runbooks/dlq-redrive.md`'s 90-day
section, which describes the same trap from the redrive side.

## Not measured here

* More than one Writer instance — the production shape. One instance drained 4,000/s in with a 29 s
  peak age; N instances share the queue and the counters sum.
* A replay across all five types at once — the corpus is one fixture, one type, one queue.
  Per-type queues mean the other four are untouched by a lorastats replay; a
  replay of all five is five of these running together.
* Whether Heimdal honours 2,667/s. It cannot be measured from here.
