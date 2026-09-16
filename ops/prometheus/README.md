# DLQ alert rules

`dlq-alerts.yml` is loaded by Prometheus and evaluated from the Writer's existing
`/actuator/prometheus` scrape. Grafana reads the Prometheus alert state for alert
visibility and the platform configuration routes notifications to the approved contact
points.

The rules aggregate replica observations with `max by (type)`, never `sum`, because
every Writer replica observes the same broker queue:

- `StatsAppDlqContainsMessages` fires immediately when a per-type DLQ is nonempty.
- `StatsAppDlqUndisposedWarning` fires after the DLQ remains nonempty for 7 days.
- `StatsAppDlqUndisposedCritical` fires after it remains nonempty for 12 days.

The 7-day and 12-day rules measure a continuously nonempty DLQ. They do not inspect,
consume or requeue a message, and they reset if the DLQ becomes empty. The queue still
has no TTL and no automatic consumer; disposition remains a manual operation governed
by `docs/runbooks/dlq-redrive.md`.

Validate syntax and alert timing with the pinned Prometheus tool image:

```bash
docker run --rm \
  --entrypoint promtool \
  --volume "$PWD/ops/prometheus:/work:ro" \
  --workdir /work \
  prom/prometheus:v3.5.0 \
  check rules dlq-alerts.yml

docker run --rm \
  --entrypoint promtool \
  --volume "$PWD/ops/prometheus:/work:ro" \
  --workdir /work \
  prom/prometheus:v3.5.0 \
  test rules dlq-alerts.test.yml
```
