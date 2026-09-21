# Writer metrics contract — T-027

Requirement: STA-27A. Delivery and persistence semantics follow ADR-005 and STA-25A.
Decisions: DL-136, DL-137, DL-147 (oldest-message age), DL-148 (every series below is
registered per served type — since T-035 that is `lorastats` and `sysstats`, each writer
registering its own `type` label unconditionally so the series export at zero from
startup). All metrics below carry only `type`, taken from the consumer descriptor, never
from the untrusted payload. Prometheus counters add `_total`; timer exports use seconds.

## Delivery and persistence counters

| Metric | Meaning |
|---|---|
| `records_consumed` | One delivery entering the listener, before decoding. A redelivery is another attempt. Includes malformed bodies. |
| `records_persisted` | One successfully committed inserted row. Duplicates and failed or rolled-back transactions do not increment it. |
| `records_processing_duration` | Time from listener entry through processing, failure hand-off if needed, and the ACK attempt. Recorded on every exit, including unconfirmed hand-offs. Excludes time waiting on the queue and retry-tier TTL. |
| `records_postgres_errors` | One failed record write with a SQL, Spring data-access or transaction exception in its bounded cause chain. Includes connection and commit failures; excludes mapping and validation failures, which are `records_payload_rejected{reason="unmappable"}` (DL-126). |
| `records_write_failed` | All failed INSERT attempts. Database errors also increment this aggregate — do not sum the two as disjoint. |

No payload, `uid`, `tenant`, `clsbox`, exception message or SQL text is a metric label.
Database failures are counted per failed record attempt, not per nested exception.
`records_consumed` measures attempts, so it need not equal the number of distinct
records or of committed inserts. Consumer ACK ordering is unchanged.

The late-arrival counter `records_expired_on_arrival{type}` is **not** defined here: it
belongs to `RetentionGuard` (T-026, DL-133), which skips, meters and ACKs a record whose
`ts.device` is *strictly before* the retention cutoff — the boundary belongs to the
retained side, the same rule the sweep applies.

## Operational gauges

All gauges carry only `type`. `stats.metrics.poll-interval=PT30S` controls collection
and `stats.metrics.initial-delay=PT30S` lets the topology declare itself first. A scrape
reads cached observations and performs no broker I/O.

| Metric | Meaning |
|---|---|
| `queue_depth` | Main-queue **ready** messages from passive queue inspection. Excludes in-flight (unacknowledged) messages and the retry-tier queues. |
| `dlq_depth` | Ready messages on the per-type DLQ. The series T-060 watches; the queue carries no TTL (ADR-002 §12). |
| `consumer_availability` | Broker-reported active consumer count on the main queue, across replicas. Zero means no registered consumer; positive does not itself prove PostgreSQL is healthy. |
| `broker_metrics_collection_success` | 1 if the latest observation succeeded, otherwise 0. |
| `broker_metrics_last_success_timestamp_seconds` | Unix time of the last successful observation; 0 until there has been one. |
| `retention_period_days` | The configured `ts.device` retention window for the type, read from `RetentionWindow` — the single definition the sweep and the guard use (DL-133). |
| `queue_oldest_message_age_seconds` | **The series T-050 alerts on (DL-147).** Age of the oldest ready message on the main queue: sampled at each delivery's listener entry as receive time minus the AMQP `timestamp` Input stamps at publish — on a FIFO queue the delivery in hand *was* the oldest ready message when the broker handed it over — and **set to zero by the poll whenever it finds the queue empty**. NaN until the first observation. A retry re-entry (`x-stats-attempt` present) and a delivery with no timestamp are **not** sampled; a negative age (host clock skew) clamps to zero. Resolution is one second (AMQP timestamps are whole seconds). |

A missing queue or a broker fault produces NaN on the depth and consumer gauges, a zero
collection-success gauge and a safe `WARN`; the last-success timestamp is unchanged.
Consumers of these metrics must check success and freshness before treating a zero as
healthy. Replica-level broker observations describe the same shared resource: aggregate
with `max`, not `sum`, across replicas. Main-queue depth is a diagnostic, not the alert —
under jitter (DL-104) a cyclic depth spike is evidence, not the buffer working; the alert
is on `queue_oldest_message_age_seconds`.

**The age gauge has a blind spot, and it is covered by rules, not by a guess.** Sampled at
delivery, it stops moving when deliveries stop — an absent or wedged consumer leaves the
last value on the scrape. `ops/prometheus/writer-alerts.yml` therefore reads it together
with what the poll *does* know: `consumer_availability == 0` on a successful poll, and
`queue_depth > 0` with no `records_consumed` increase for ten minutes. The three are one
alert; `ops/prometheus/README.md` says how to read them. A failed poll leaves the gauge
alone, exactly as it leaves the others NaN.

Prometheus evaluates `ops/prometheus/dlq-alerts.yml` against `dlq_depth` (T-060) and
`ops/prometheus/writer-alerts.yml` against the age gauge, the consumer gauges,
`records_expired_on_arrival` and `records_upstream_status_not_ok` (T-050). The DLQ rules
aggregate replicas with `max by (type)` and alert immediately, after 7 continuous days,
and after 12 continuous days; a nonempty-duration rule resets when the queue becomes
empty, and nothing inspects, consumes or requeues a DLQ message. The DLQ's own
oldest-message age is still owed (DL-144): the publish timestamp now travels with every
retry and DLQ copy, so a broker-side reader can be added later against it.

Retention health is **not** polled from here. The sweep reports
`retention_oldest_partition_age_days{type}` after each run, alongside
`retention_partitions_dropped{type}` and `retention_orphans_dropped{type}` (T-025,
DL-132); a gauge sampled on a timer would keep reporting a healthy number from a replica
whose sweep never ran.

**Since DL-156 (T-054) the same rule this document gives the broker gauges applies to the
retention gauges: never observed is `NaN`, never zero.** `retention_oldest_partition_age_days`
is `NaN` until the first completed sweep on that replica — the first stack run found a
replica that had not swept for 29 hours reading `0.0`, a healthy number from a sweep that
never happened. The sweep also stamps **`retention_last_sweep_timestamp_seconds{type}`**
(Unix time, `NaN` until the replica's first completed sweep) as its last act, so a sweep that
died half-way reports nothing. Under ADR-003 D8 only the replica that wins the advisory lock
stamps it, so read it as `max by (type)` — `max` ignores `NaN` unless every replica is `NaN`,
which is the "never ran anywhere" case `StatsAppRetentionSweepMissing` catches on its
second arm.

## Not here: the Q13b histogram

`records_ts_server_cycle_offset_seconds{type}` — `ts.server mod 600` in twenty
30-second buckets, the measurement DL-104 requires before hardware is sized down — is
an **Input** metric (DL-147): Input sees every type from the first day, while the Writer
is deployed per type. It and `records_ts_device_corrected{type}` are described in
`ops/prometheus/README.md` and `heimdal-input-contract-v1.md`.

## Verification

`QueueAgeMetricsTest` covers NaN-until-observed, the sample, the clamp, both exclusions
and the empty-queue reset; `OperationalMetricsTest` proves the poll registers the gauge
and zeroes it on an empty queue only; both consumer unit tests prove the sample is taken
at entry; `ManagementSurfaceTest` proves the series reaches the scrape.

`OperationalMetricsIntegrationTest` uses dedicated PostgreSQL and RabbitMQ containers —
it stops the listener to build a visible backlog, which on the shared broker another
context's listener would drain — and proves ready backlog, listener stop/start, a
persisted record's counters and the HTTP Prometheus output. `OperationalMetricsTest`
covers unknown, empty, unavailable and recovered observations, no I/O during a scrape,
and that the exported window is the one in force. `ManagementSurfaceTest` proves every
series above reaches the scrape in a context with no live consumer.
