# Burst load test

**13,333/s is the configured design headroom · 2,667/s is the agreed (and unenforceable)
replay cap · 4,000 req/s is the per-instance ingest limit**

## What is being measured, and why it is the confirm

A 2xx from Input is returned only after the broker's publisher confirm.
Under a burst, throughput is not what fails first — **confirm latency** is: every request
holds a servlet thread for the length of the confirm wait, and when that wait grows the
thread pool fills, requests queue, and the confirm timeout (`stats.amqp.confirm-timeout`,
5 s) turns them into 503s. So the test drives Input at a fixed *offered* rate (open loop —
it does not slow down when Input does, exactly as a Heimdal replay would not) and reads
`records_publish_confirm_seconds{type,outcome}`, the histogram Input exports,
before and after the run. Client-side latency is reported beside it; the difference between
the two is queueing in front of the confirm.

## How to run

```bash
# the generator: Java 21, no dependencies, one file
java ops/load/BurstLoad.java --url http://localhost:8081/internal/stats --rate 13333 --duration 30 [--json out.json]

# the three-run drill (A, B, C below)
ops/drills/burst-load.sh [url] [seconds]
```

Every record gets a unique `uid` and `ts.*` re-stamped to now, so nothing is absorbed as
a duplicate and nothing trips the clock-skew or late-arrival gates — which also means the
Writer stores all of it. Run against a database you are willing to fill.

## First measurement — 21 September 2026

One Input instance from this branch, run locally (`java -jar`, port 18081) against the
Compose RabbitMQ on the same laptop as the generator, the Writer, PostgreSQL and Keycloak.
Absolute numbers are therefore conservative; the *shape* is the finding.

| Run | Offered | Achieved | 200 | 429 | 503 | Client p50 / p99 | **Confirm p50 / p99** (server) |
|---|---|---|---|---|---|---|---|
| **A** — the shipped 4,000/s per-instance limit | 4,000/s × 20 s | 3,999/s | 80,000 | 0 | 0 | 2.3 / 14.6 ms | **2.4 / 15.7 ms** |
| **B** — 13,333/s against that limit | 13,333/s × 15 s | 13,206/s | 63,854 | 134,263 | 0 | 1.0 / 190 ms | 4.1 / 49.5 ms |
| **C** — 13,333/s, limit lifted to 20,000 | 13,333/s × 20 s | **11,426/s** | 230,219 | 0 | **0** | 127 / 288 ms | **17.2 / 49.8 ms** |

Three things to carry:

1. **At the design headroom the confirm path holds.** Run C: 230,219 confirmed records,
   not one 503, confirm p99 **49.8 ms** — one hundredth of the 5 s timeout. The broker is
   not where a burst breaks.
2. **A single instance saturates at ~11,400 req/s, and it is Tomcat, not the broker.**
   Run C's client p50 of 127 ms against a server-side confirm mean of 17 ms is queueing
   in front of the confirm: Spring Boot's default `server.tomcat.threads.max=200`, each
   thread held for one confirm wait, gives 200 ÷ 0.017 s ≈ **11,700 req/s** — which is
   where it stopped. 36,441 requests were never sent because the generator's own
   in-flight cap (2,000) filled. At 13,333/s a single instance therefore needs either
   ~700 request threads or fewer requests; the deployment shape already answers with instances.
3. **The limiter and the thread pool are sized together, and that is not a coincidence.**
   At the shipped 4,000/s limit, 4,000 × 50 ms (the p99 confirm under load) = **200
   concurrent** = Tomcat's default. Run B shows what the limiter buys: 13,206/s offered,
   ~4,250/s accepted, 134,263 × 429, **zero 503s**, confirm p99 49.5 ms on what got
   through — the 429 is the backpressure channel working, and Heimdal's retry on
   it is bounded by `maxRetry`. Raise `stats.input.limits.max-requests-per-second`
   without raising `server.tomcat.threads.max` and run C is what you get: the confirm is
   fine and the requests queue in front of it anyway.

**Sizing rule that falls out:** `threads.max ≈ max-requests-per-second × confirm p99`, and
the p99 to use is the one this test measures at that rate on that broker, not the idle one
(2.4 ms at 4,000/s became 17 ms mean at 11,400/s).

## What a bad run looks like

* **503s appearing in the per-second list** while the achieved rate is below target: the
  confirm timeout is being hit. Check `queue_oldest_message_age_seconds` on the Writer
  side — a broker that is flow-controlling publishers because a queue is filling
  slows confirms first. Read the `outcome` label: `timeout` versus `nack` versus `error`
  are different incidents.
* **`could not send` climbing** with 200s still at 100 %: the generator's in-flight cap,
  i.e. the server's thread pool is full — item 2 above. Not a broker problem.
* **429s with the limit at its production value**: expected above 4,000/s per instance;
  count instances before reading anything else.

## Not measured here

* More than one Input instance behind a balancer — the production shape. The generator takes one
  URL; run one per instance, or point it at the balancer.
* The Writer keeping up: run C left a 244,371-record backlog on `app_statsstore.lorastats`
  for the single local Writer to drain, which is the queue-age alert's job to watch, not this
  test's. The DLQ took nothing. *(Measured since by the replay drill: one Writer consumed at a peak
  3,137/s on one type — `docs/runbooks/replay-drill.md`.)*
* ~~A replay at 2,667/s on top of steady state — run A at 4,000/s covers the sum.~~
  **Measured 22 Sep 2026 — `docs/runbooks/replay-drill.md`**: the same 4,000/s, but
  two thirds of it duplicates, which is what run A cannot say anything about.
