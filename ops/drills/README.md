# Operational drills — Phase 5

Each drill measures something the design *asserts*. A target nobody measures is not a
target (DL-111), and a mechanism nobody has watched fail is a mechanism believed in. Where a
drill lives in the test suite it runs in every build, so its numbers cannot drift from what
PostgreSQL actually does without a red build saying so; where it needs the real stack it is
a script here.

| Task | What it measures | Where | Measured (21 Sep 2026, PostgreSQL 17, this laptop) |
|---|---|---|---|
| **T-051** restore | One daily partition restored and re-attached, parent online, timed against RTO 1 h | `PartitionRestoreDrillIntegrationTest`; runbook `docs/runbooks/partition-restore.md` | **1.23 s** for a 200,000-row day (dump 0.29 s, standalone restore 0.88 s, `ATTACH` 1 ms); 6,517 concurrent inserts, none failed, worst 55 ms |
| **T-054** lock contention | Two replicas (two pools) and two processes firing the maintenance cron together | `AdvisoryLockContentionIntegrationTest`; `t054-advisory-lock-contention.sh` | 20/20 simultaneous rounds: one drop each, loser skipped at DEBUG every time, pools clean. Live stack: each replica won once and lost once, no lock left, no ERROR |
| **T-055** `DETACH CONCURRENTLY` | Writer insert latency and lock waits while a 300,000-row day is detached and dropped — with a control that *can* block | `DetachConcurrentlyLatencyIntegrationTest` | Sweep 17 ms, worst insert **1.9 ms** (baseline worst 5.4 ms). Sweep behind an open reader: sweep waits 1.56 s, **13,436 inserts continue**, worst 13 ms, no insert queued. Control (plain `DETACH` behind the same reader): worst insert **1,515 ms**, inserts queued on the parent |
| **T-048** burst load | p99 publisher-confirm latency at the 13,333/s design headroom | `t048-burst-load.sh` → `ops/load/BurstLoad.java`; runbook `docs/runbooks/load-test-burst.md` | 4,000/s: confirm p99 **15.7 ms**, 80,000 × 200. 13,333/s with the 4,000/s limit: 134,263 × 429, **zero 503s**. 13,333/s with the limit lifted: **11,426/s** sustained, 230,219 × 200, zero 503s, confirm p99 **49.8 ms** — one instance saturates on Tomcat's 200 threads, not on the broker |

## T-055 — what the numbers say

`DETACH PARTITION … CONCURRENTLY` takes `SHARE UPDATE EXCLUSIVE` on the parent; a plain
`DETACH` takes `ACCESS EXCLUSIVE`. The difference is not visible when nothing else is
running — phase 2 and a plain detach on an idle parent both finish in milliseconds. It is
visible the moment any transaction holds the parent: phase 3 and phase 4 are the *same*
reader holding an open transaction for 1.5 s, and the writer either does not notice
(concurrently: worst 13 ms) or stops dead until the reader lets go (plain: worst 1,515 ms,
an insert seen waiting on the parent's lock). Two readings to carry:

* **`DETACH CONCURRENTLY` waits for transactions that reference the partition.** Phase 3's
  sweep took 1.56 s for a 17 ms job. In production that reader is an API page or a
  long-running report, and the sweep looking slow is the harness, not the mechanism —
  the writer is the thing to measure, and it was unaffected.
* **Every phase ends with `pg_inherits.inhdetachpending` at zero.** A measurement run that
  cancels a detach — `statement_timeout`, Ctrl-C, killing the container — leaves the
  partition pending detach and every later detach on that table failing while `INSERT`
  keeps working (trap 27, DL-132). The built sweep self-heals on its next run; a run that
  walks away without checking has wedged the environment with no visible symptom.

## Running the scripts

```bash
docker compose --profile apps up -d          # the stack, from the repository's docker/
ops/drills/t054-advisory-lock-contention.sh   # ~80 s; two extra Writer containers, removed afterwards
```
