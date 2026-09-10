# Writer metrics contract — T-027

Requirement: STA-27A. Delivery and persistence semantics follow ADR-005 and STA-25A.
Decisions: DL-136, DL-137. All metrics below carry only `type`, taken from the consumer
descriptor, never from the untrusted payload. Prometheus counters add `_total`; timer
exports use seconds.

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

A missing queue or a broker fault produces NaN on the depth and consumer gauges, a zero
collection-success gauge and a safe `WARN`; the last-success timestamp is unchanged.
Consumers of these metrics must check success and freshness before treating a zero as
healthy. Replica-level broker observations describe the same shared resource: aggregate
with `max`, not `sum`, across replicas. Main-queue depth is a diagnostic, not the
oldest-message-age alert T-050 requires — under jitter (DL-104) a cyclic depth spike is
evidence, not the buffer working.

Retention health is **not** polled from here. The sweep reports
`retention_oldest_partition_age_days{type}` after each run, alongside
`retention_partitions_dropped{type}` and `retention_orphans_dropped{type}` (T-025,
DL-132); a gauge sampled on a timer would keep reporting a healthy number from a replica
whose sweep never ran.

## Verification

`OperationalMetricsIntegrationTest` uses dedicated PostgreSQL and RabbitMQ containers —
it stops the listener to build a visible backlog, which on the shared broker another
context's listener would drain — and proves ready backlog, listener stop/start, a
persisted record's counters and the HTTP Prometheus output. `OperationalMetricsTest`
covers unknown, empty, unavailable and recovered observations, no I/O during a scrape,
and that the exported window is the one in force. `ManagementSurfaceTest` proves every
series above reaches the scrape in a context with no live consumer.
