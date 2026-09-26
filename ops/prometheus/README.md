# Prometheus alert rules

Three rule files, all loaded by Prometheus: two evaluated from the Writer's existing
`/actuator/prometheus` scrape, one from `postgres_exporter`. Grafana reads the Prometheus alert state for visibility and
the platform configuration routes notifications to the approved contact points. Nothing in
either file inspects, consumes or requeues a message, and there is no application
property that pretends to configure an external evaluator.

| File | Task | Watches |
|---|---|---|
| `dlq-alerts.yml` | T-060 (DL-144) | `dlq_depth` — non-empty now, for 7 days, for 12 days |
| `writer-alerts.yml` | T-050 (DL-147), T-054 (DL-156) | `queue_oldest_message_age_seconds`, `consumer_availability`, `queue_depth` + `records_consumed`, `broker_metrics_collection_success`, `records_expired_on_arrival`, `records_upstream_status_not_ok`, `retention_last_sweep_timestamp_seconds` + `process_uptime_seconds` |
| `postgres-alerts.yml` | T-051 (DL-155) | `pg_stat_archiver_failed_count`, `pg_stat_archiver_last_archive_age`, `pg_settings_archive_mode`, `pg_up` — the WAL archiver, i.e. whether RPO ≤ 5 min (ADR-003 D5, DL-111) is actually being met |

`prometheus.yml` is the scrape configuration the Compose `observability` profile mounts
(`docker compose --profile apps --profile observability up -d`): the three modules and
`postgres_exporter`, evaluating all three rule files. On any other deployment replace the
static targets and keep the rules.

Every rule aggregates replica observations with `max by (type)` — each Writer replica
polls the same broker queue (DL-137) — or sums per-replica counters, which are genuinely
per replica. Never `sum` a gauge.

## Main queues — age, never depth

Under jitter (Q13b, DL-104) a cyclic depth spike on `app_statsstore.<type>` is not the
buffer working; it is evidence the jitter answer is wrong. The signal that says "the
Writer is not keeping up" is how long the oldest message has waited.

`queue_oldest_message_age_seconds{type}` is sampled at delivery (DL-147): the delivery in
hand was the head of a FIFO queue when the broker handed it over, so its age at listener
entry — receive time minus the AMQP `timestamp` Input stamps at publish — is the queue's
oldest-message age at that moment. The Writer's poll zeroes it whenever the queue is
empty. **It therefore stops moving when deliveries stop**, and three rules read it as one
alert:

| Alert | Fires when | Which failure |
|---|---|---|
| `StatsAppMainQueueAging` (warning) | age > 300 s for 5 m | The Writer is consuming and behind — a fresh record has waited longer than tier 2's retry delay |
| `StatsAppMainQueueAgingCritical` (critical) | age > 1,800 s for 5 m | Behind by more than the longest retry tier; a Heimdal replay on top of this will sit at the 503 backpressure channel |
| `StatsAppMainQueueNoConsumer` (critical) | `consumer_availability == 0` **and** the poll succeeded, for 2 m | Nothing is consuming, so nothing is sampled — the gauge cannot say so |
| `StatsAppConsumerStalled` (critical) | `queue_depth > 0` and no `records_consumed` increase in 10 m | A consumer is registered and taking nothing — a wedged listener or a hung connection |
| `StatsAppBrokerObservationStale` (warning) | `broker_metrics_collection_success == 0` for 5 m | A failed poll writes NaN, and NaN compares false to everything: the four rules above go *quiet*, not wrong. This is what says they have gone quiet |

Read them together. Age alone misses the two cases where the consumer is not moving;
the consumer rules alone miss the case where it moves too slowly. The thresholds are
engineering defaults; change them against measurement and record the change.

The two age exclusions are deliberate: a delivery back from a retry tier
(`x-stats-attempt` present) has waited exactly that tier's delay and is the retry arm's
business (`records_retried`, `records_write_failed`), and a delivery with no timestamp is
skipped rather than guessed at. The DLQ's own age is **not** in the gauge — the DLQ has no
consumer to sample it — which is why `dlq-alerts.yml` still alerts on how long the queue
has been continuously non-empty (DL-144).

## Late arrival — D5b's signed obligation

`StatsAppLateArrival` (warning) fires on
`sum by (type) (increase(records_expired_on_arrival_total[1h])) > 0`: a valid record
arrived with `ts.device` already past the retention window and was skipped, metered and
ACKed (DL-133). One late record alerts for the following hour; a fleet doing it every
cycle keeps it firing. D5b says "alert when non-zero", and a counter is non-zero forever
once it has moved, so the rule is on the *increase*.

If a DLQ redrive is in progress when this fires, it is the ~90-day trap
(`docs/runbooks/dlq-redrive.md`): the redriven records are too old to store and are not
being recovered. Stop the redrive.

## Upstream status — a PROVISIONAL threshold

`StatsAppUpstreamStatusNotOk` (warning) fires when, for a sustained hour, more than **1 %**
of consumed records carried a `status` other than exactly `["conversion ok"]`:

```promql
sum by (type) (increase(records_upstream_status_not_ok_total[1h]))
  / sum by (type) (increase(records_consumed_total[1h])) > 0.01
```

**The 1 % is provisional and is written as such in the rule.** The counter is
value-agnostic by design (DL-108, DL-128), and DL-139 found that some upstream documents
carry `["ok"]` where stats records carry `["conversion ok"]`, so a healthy baseline may be
non-zero. After the first 24 hours of ingest:

```promql
# the observed baseline, per type, over the first day
sum by (type) (increase(records_upstream_status_not_ok_total[24h]))
  / sum by (type) (increase(records_consumed_total[24h]))
```

Set the threshold to a small multiple of that baseline, update the rule, the test in
`writer-alerts.test.yml` and this paragraph, and record the number against the DL-128
residual in `claude/DECISIONS.md` §12. Do not set it to zero, and do not leave it at 1 %
because nobody looked.

## The publisher-confirm histogram — an Input metric (T-048, DL-159)

`records_publish_confirm_seconds{type,outcome}` times every publish from hand-off to the
broker's answer (`confirmed` / `nack` / `returned` / `timeout` / `error`), fixed edges 1 ms
… 5 s. `histogram_quantile(0.99, sum by (type, le) (rate(records_publish_confirm_seconds_bucket{outcome="confirmed"}[5m])))`
is the number that turns a burst into 503s; measured at 15.7 ms (4,000/s) and 49.8 ms
(11,400/s) in `docs/runbooks/load-test-burst.md`. No alert on it yet: the confirm timeout
is 5 s and the 503 rate on `records_publish_failed` already says when it is hit.

## The Q13b histogram — an Input metric, not an alert

> **Every `stats-input` series except `records_unroutable` carries `source="internal"|"external"`
> since 21 Sep 2026 (DL-151)** — which ingest door the request used. The queries below
> already aggregate with `sum by (type, le)` / `sum by (type)`, so they are unaffected; a
> new query that reads an Input series per type must do the same or filter on `source`, or
> it sees two series. Writer series are unlabelled by door — the Writer does not know it.

`records_ts_server_cycle_offset_seconds{type}` is exported by **stats-input** (DL-147):
`ts.server mod 600` for every accepted record, in twenty cumulative 30-second buckets. It
answers the question DL-104 made the precondition for sizing hardware down — do gateways
report on synchronised ten-minute boundaries, or jitter across them?

```promql
# share of a type's records landing in each 30 s slot of the cycle (Grafana heatmap / bar)
sum by (type, le) (increase(records_ts_server_cycle_offset_seconds_bucket[24h]))
  / ignoring(le) group_left sum by (type) (increase(records_ts_server_cycle_offset_seconds_count[24h]))
```

Read the *non-cumulative* slot shares (Grafana's histogram transform, or subtract
adjacent `le` values). Jitter is twenty slots at roughly 5 % each; a synchronised fleet
is one or two slots holding most of the mass. Hardware is not sized down until 24 hours
of this say jitter (T-050, DL-104). One data point already: a device seen on 18 Sep 2026
reports `ltestats` every ~29 minutes, not 10 (DL-146).

Beside it, `records_ts_device_corrected{type}` counts records Heimdal flagged with
`meta.tsDeviceCorrected` (DL-146) — the number to put beside Q15f. Neither is alerted on.

## Dashboards

`ops/grafana/` holds the three generated Grafana dashboards (T-056, DL-158) and
`check-dashboards.py`, which also validates every metric name in the rule files here.

## Validate

Syntax and behaviour, with the pinned Prometheus tool image (CI runs the same two commands):

```bash
docker run --rm \
  --entrypoint promtool \
  --volume "$PWD/ops/prometheus:/work:ro" \
  --workdir /work \
  prom/prometheus:v3.5.0 \
  check rules dlq-alerts.yml writer-alerts.yml postgres-alerts.yml

docker run --rm \
  --entrypoint promtool \
  --volume "$PWD/ops/prometheus:/work:ro" \
  --workdir /work \
  prom/prometheus:v3.5.0 \
  test rules dlq-alerts.test.yml writer-alerts.test.yml postgres-alerts.test.yml
```

`writer-alerts.test.yml` covers: a healthy queue raising nothing; warning without
critical; critical with warning; replica `max`; NaN never firing and the stale-observation
rule firing instead; no consumer after two minutes; a stalled consumer versus a quiet one
on an empty queue; late arrival firing for an hour and clearing; the ratio breaching
after its pending hour; and a baseline below threshold, with zero traffic not dividing
into an alert.

## WAL archiver — the RPO half of ADR-003 D5

`postgres-alerts.yml` exists because the failure it watches has no other symptom. With
`archive_mode=on` the archiver retries a failing `archive_command` forever and reports it
only in `pg_stat_archiver`: the database runs, every Writer counter reads healthy, and the
recovery point quietly stops moving. Measured on 21 Sep 2026: a fresh Compose volume is
created root-owned, so the postgres user could not write the archive — `failed_count=7`,
`archived_count=0`, nothing anywhere else. `docker-compose.yml` now owns the directory
before the server starts; the rule is what catches the next cause.

| Rule | Fires when | Severity |
|---|---|---|
| `StatsAppWalArchiveFailing` | `archive_command` has failed at least once in each of the last five minutes | critical |
| `StatsAppWalArchiveStale` | nothing archived for 15 min while `archive_mode` is on — a hanging command counts no failure, the age just climbs | critical |
| `StatsAppWalArchiveDisabled` | `archive_mode` off for 30 min — a configuration warning, expected on a scratch database | warning |
| `StatsAppPostgresExporterDown` | `pg_up == 0` for 5 min — every rule above is blind while this is true | warning |

Three of the four series are `postgres_exporter`'s built-in `pg_stat_archiver` collector
(v0.15+), verified on the first live scrape on 22 Sep 2026 (DL-160). **The fourth is not
built in.** The exporter's settings collector exports only numeric and boolean settings;
`archive_mode` is an enum (`off | on | always`) and is skipped — so `pg_settings_archive_mode`
did not exist, `StatsAppWalArchiveStale` (gated on it) and `StatsAppWalArchiveDisabled`
(reading it) matched nothing, and both were silent by construction with `promtool` green.
`ops/postgres-exporter/queries.yml` is the custom query that exports it; the Compose profile
loads it with `--extend.query-path`. On any other exporter deployment, carry that query — or
the two rules are decoration. The runbook they point at is `docs/runbooks/partition-restore.md`.

## Retention sweep missing — T-054's alert

`StatsAppRetentionSweepMissing` (critical, `for: 30m`) fires when no replica has completed
a retention sweep for a type in over 26 hours. It reads
`retention_last_sweep_timestamp_seconds{type}`, which the sweep stamps as its last act
(DL-156), aggregated `max by (type)` because only the lock winner stamps it (ADR-003 D8).
The gauge is `NaN` until a replica's first sweep; `max` ignores `NaN` unless every replica
is `NaN`, and that case — the sweep has never run anywhere — is the rule's second arm,
gated on `process_uptime_seconds > 26h` so a fresh deployment is not paged before its
first scheduled pass. Two causes it exists for, neither with any other symptom: an
advisory lock leaked by a dead replica's pooled connection (which starves every replica of
that type), and a scheduler that never fires — the first Compose run went 29 hours without
a pass while every gauge read healthy, because the host had slept through the cron slot.

