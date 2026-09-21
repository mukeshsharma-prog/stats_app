# Prometheus alert rules

Two rule files, both loaded by Prometheus and evaluated from the Writer's existing
`/actuator/prometheus` scrape. Grafana reads the Prometheus alert state for visibility and
the platform configuration routes notifications to the approved contact points. Nothing in
either file inspects, consumes or requeues a message, and there is no application
property that pretends to configure an external evaluator.

| File | Task | Watches |
|---|---|---|
| `dlq-alerts.yml` | T-060 (DL-144) | `dlq_depth` — non-empty now, for 7 days, for 12 days |
| `writer-alerts.yml` | T-050 (DL-147) | `queue_oldest_message_age_seconds`, `consumer_availability`, `queue_depth` + `records_consumed`, `broker_metrics_collection_success`, `records_expired_on_arrival`, `records_upstream_status_not_ok` |

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

## Validate

Syntax and behaviour, with the pinned Prometheus tool image (CI runs the same two commands):

```bash
docker run --rm \
  --entrypoint promtool \
  --volume "$PWD/ops/prometheus:/work:ro" \
  --workdir /work \
  prom/prometheus:v3.5.0 \
  check rules dlq-alerts.yml writer-alerts.yml

docker run --rm \
  --entrypoint promtool \
  --volume "$PWD/ops/prometheus:/work:ro" \
  --workdir /work \
  prom/prometheus:v3.5.0 \
  test rules dlq-alerts.test.yml writer-alerts.test.yml
```

`writer-alerts.test.yml` covers: a healthy queue raising nothing; warning without
critical; critical with warning; replica `max`; NaN never firing and the stale-observation
rule firing instead; no consumer after two minutes; a stalled consumer versus a quiet one
on an empty queue; late arrival firing for an hour and clearing; the ratio breaching
after its pending hour; and a baseline below threshold, with zero traffic not dividing
into an alert.
