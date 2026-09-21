#!/usr/bin/env python3
"""Generates the Stats App Grafana dashboards (T-056, DL-158).

The JSON files beside this script are its output, committed so Grafana can provision them
and so a reviewer can read them; check-dashboards.py regenerates and diffs, so the two
cannot drift. Panels are declared here as (title, expression, unit, kind) so that the two
rules every panel must follow are written once:

  * broker gauges are aggregated across replicas with max, never sum (DL-137) -- each
    replica polls the same shared queue; summing N replicas reports N times the backlog;
  * every broker gauge panel renders broker_metrics_collection_success beside it, because
    a failed poll is NaN by design and a panel that hides NaN shows an empty queue.

Input series carry a `source` label since DL-151 and are summed over it.
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "dashboards"


def _target(expr, legend):
    return {"datasource": {"type": "prometheus", "uid": "prometheus"}, "expr": expr, "legendFormat": legend, "refId": "A"}


def panel(pid, title, targets, unit="short", kind="timeseries", w=12, h=8, x=0, y=0, description="", thresholds=None):
    p = {
        "id": pid, "type": kind, "title": title, "description": description,
        "datasource": {"type": "prometheus", "uid": "prometheus"},
        "gridPos": {"h": h, "w": w, "x": x, "y": y},
        "targets": [dict(t, refId=chr(65 + i)) for i, t in enumerate(targets)],
        "fieldConfig": {"defaults": {"unit": unit}, "overrides": []},
        "options": {},
    }
    if kind == "timeseries":
        p["options"] = {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}}
    if kind == "stat":
        p["options"] = {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "background", "graphMode": "none"}
    if kind == "heatmap":
        p["options"] = {"calculate": False, "yAxis": {"unit": "s"}}
    if thresholds:
        p["fieldConfig"]["defaults"]["thresholds"] = {"mode": "absolute", "steps": thresholds}
    return p


def row(pid, title, y):
    return {"id": pid, "type": "row", "title": title, "collapsed": False, "gridPos": {"h": 1, "w": 24, "x": 0, "y": y}, "panels": []}


def dashboard(uid, title, description, panels, tags):
    return {
        "uid": uid, "title": title, "description": description, "tags": tags, "editable": False,
        "schemaVersion": 39, "version": 1, "time": {"from": "now-6h", "to": "now"}, "refresh": "30s",
        "timezone": "utc", "templating": {"list": []}, "annotations": {"list": []}, "panels": panels,
    }


def layout(sections):
    """sections: list of (row title, [panel dicts without gridPos y]) -> panels with y assigned, two per line."""
    out, y, pid = [], 0, 1
    for title, ps in sections:
        out.append(row(pid, title, y)); pid += 1; y += 1
        line_h = 0
        for i, p in enumerate(ps):
            p = dict(p); p["id"] = pid; pid += 1
            g = p["gridPos"]
            if i % 2 == 0 and i > 0:
                y += line_h; line_h = 0
            g["x"] = 0 if i % 2 == 0 else 12
            g["y"] = y
            line_h = max(line_h, g["h"])
            out.append(p)
        y += line_h
    return out


OK_GREEN_RED = [{"color": "red", "value": None}, {"color": "green", "value": 1}]
RED_GREEN = [{"color": "green", "value": None}, {"color": "red", "value": 1}]

# ---------------------------------------------------------------- Writer ----
writer = layout([
    ("Throughput — per type (sum of per-replica counters)", [
        panel(0, "Persisted records / s", [_target('sum by (type) (rate(records_persisted_total[5m]))', "{{type}}")], "reqps",
              description="Records inserted in committed transactions. Compare with consumed: the gap is duplicates, skips and failures."),
        panel(0, "Consumed vs persisted / s", [
            _target('sum (rate(records_consumed_total[5m]))', "consumed"),
            _target('sum (rate(records_persisted_total[5m]))', "persisted"),
            _target('sum (rate(records_duplicate_suppressed_total[5m]))', "duplicate suppressed"),
            _target('sum (rate(records_expired_on_arrival_total[5m]))', "expired on arrival (D5b)")], "reqps"),
        panel(0, "lorastats dialect mix / s (DL-127)", [_target('sum by (dialect) (rate(records_lorastats_dialect_total[5m]))', "{{dialect}}")], "reqps",
              description="One going to zero is the DL-105 residual: a dialect being retired."),
        panel(0, "Unmapped data members preserved / s (D14b)", [_target('sum by (type) (rate(records_unmapped_data_field_total[5m]))', "{{type}}")], "reqps",
              description="Non-zero on ordinary traffic means Heimdal added a field — or a dialect was mapped from the wire shape (trap 22)."),
    ]),
    ("Errors — every one of these is a record that did not land the normal way", [
        panel(0, "Write failures and PostgreSQL errors / s", [
            _target('sum by (type) (rate(records_write_failed_total[5m]))', "write_failed {{type}}"),
            _target('sum by (type) (rate(records_postgres_errors_total[5m]))', "postgres_errors {{type}}")], "reqps",
              description="write_failed means the INSERT was attempted and refused (DL-126). Retried through three tiers unless permanent."),
        panel(0, "Payload rejected at the gate / s, by reason (DL-126)", [_target('sum by (type, reason) (rate(records_payload_rejected_total[5m]))', "{{type}} {{reason}}")], "reqps",
              description="payload_schema: a contract conversation with Heimdal. type_mismatch: a routing fault on our side. unmappable: a value the column cannot hold (WireNumbers)."),
        panel(0, "Dead-lettered / s, by failure class", [_target('sum by (type, reason) (rate(records_dead_lettered_total[5m]))', "{{type}} {{reason}}")], "reqps",
              description="Appears on first event: the reason label is an exception class name and cannot be pre-registered. dlq_depth below is the durable view."),
        panel(0, "Retried / s, by tier", [_target('sum by (type, tier) (rate(records_retried_total[5m]))', "{{type}} tier {{tier}}")], "reqps",
              description="Appears on first retry. A steady tier-3 rate is a failure that will reach the DLQ 35 minutes after it started."),
        panel(0, "Upstream status not OK — share of consumed (1h)", [
            _target('sum by (type) (increase(records_upstream_status_not_ok_total[1h])) / sum by (type) (increase(records_consumed_total[1h]))', "{{type}}")], "percentunit",
              description="Stored regardless (DL-108). The alert threshold is PROVISIONAL at 1% until the first 24 h set it (DL-128 residual)."),
        panel(0, "Expired on arrival / h (D5b — deliberate loss)", [_target('sum by (type) (increase(records_expired_on_arrival_total[1h]))', "{{type}}")], "short",
              description="A gateway back from a long outage does this once. A fleet doing it every cycle is a clock problem upstream — or a DLQ redrive past the window."),
    ]),
    ("Latency — delivery processing (Timer: mean and max; no histogram is exported)", [
        panel(0, "Mean processing time per delivery", [
            _target('sum by (type) (rate(records_processing_duration_seconds_sum[5m])) / sum by (type) (rate(records_processing_duration_seconds_count[5m]))', "{{type}}")], "s",
              description="Includes persistence, failure hand-off and the ACK. Mean of a 5-minute window; the max panel catches the tail."),
        panel(0, "Max processing time per delivery (per replica)", [_target('max by (type) (records_processing_duration_seconds_max)', "{{type}}")], "s"),
    ]),
    ("Queues — one shared broker, N replicas: aggregate gauges with max, and read them WITH the poll status", [
        panel(0, "Oldest message age on app_statsstore.<type> (T-050)", [_target('max by (type) (queue_oldest_message_age_seconds)', "{{type}}")], "s",
              description="Sampled at delivery; stops moving when deliveries stop — read with 'no consumer' and 'consumer stalled' (DL-147). Alert at 300 s / 1,800 s.",
              thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 300}, {"color": "red", "value": 1800}]),
        panel(0, "Broker poll succeeded (1 = the gauges on this row are trustworthy)", [_target('min by (type) (broker_metrics_collection_success)', "{{type}}")], "short", kind="stat",
              description="A failed poll writes NaN into every broker gauge by design (DL-137). When this is 0 the depth and age panels are blind, not empty.",
              thresholds=OK_GREEN_RED),
        panel(0, "Main queue depth (diagnostic — never the alert, Q13b)", [_target('max by (type) (queue_depth)', "{{type}}")], "short",
              description="Under jitter a cyclic depth spike is evidence the jitter answer is wrong, not the buffer working (DL-104)."),
        panel(0, "DLQ depth (T-060) — no TTL, manual disposition within 14 days", [_target('max by (type) (dlq_depth)', "{{type}}")], "short",
              thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 1}]),
        panel(0, "Consumer availability (consumers registered on the main queue)", [_target('max by (type) (consumer_availability)', "{{type}}")], "short", kind="stat", thresholds=OK_GREEN_RED),
        panel(0, "Seconds since the last successful broker poll", [_target('time() - max by (type) (broker_metrics_last_success_timestamp_seconds)', "{{type}}")], "s",
              thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 120}]),
    ]),
    ("Retention — the sweep reports; nothing here is polled (DL-132, DL-156)", [
        panel(0, "Oldest live partition age vs the configured window", [
            _target('max by (type) (retention_oldest_partition_age_days)', "oldest {{type}}"),
            _target('max by (type) (retention_period_days)', "window {{type}}")], "d",
              description="NaN until the first sweep on a replica (DL-156). Sits just under the window when healthy; climbing past it means the sweep has stopped."),
        panel(0, "Hours since the last completed sweep (any replica)", [_target('(time() - max by (type) (retention_last_sweep_timestamp_seconds)) / 3600', "{{type}}")], "h",
              description="Only the lock winner stamps it (D8) — max across replicas. Empty means no replica has ever swept: StatsAppRetentionSweepMissing after 26 h.",
              thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 26}]),
        panel(0, "Partitions dropped / day", [_target('sum by (type) (increase(retention_partitions_dropped_total[1d]))', "{{type}}")], "short",
              description="One per type per day is the steady state."),
        panel(0, "Orphans dropped / day (a previous run died between DETACH and DROP)", [_target('sum by (type) (increase(retention_orphans_dropped_total[1d]))', "{{type}}")], "short",
              thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 1}]),
    ]),
    ("Readiness and resources", [
        panel(0, "Writer replicas up (Prometheus scrape)", [_target('sum(up{job="stats-writer"})', "replicas")], "short", kind="stat", thresholds=OK_GREEN_RED),
        panel(0, "HikariCP active connections (per replica)", [_target('hikaricp_connections_active', "{{instance}}")], "short",
              description="The advisory lock is session-level: a connection that never returns to idle after maintenance is the leak T-054 guards against."),
        panel(0, "JVM heap used (per replica)", [_target('sum by (instance) (jvm_memory_used_bytes{area="heap"})', "{{instance}}")], "bytes"),
        panel(0, "Actuator requests / s by status", [_target('sum by (status) (rate(http_server_requests_seconds_count{job="stats-writer"}[5m]))', "{{status}}")], "reqps"),
    ]),
])

# ----------------------------------------------------------------- Input ----
ingest = layout([
    ("Ingest — two doors, one handler (DL-151); Input series are summed over source", [
        panel(0, "Accepted / s by type", [_target('sum by (type) (rate(records_accepted_total[5m]))', "{{type}}")], "reqps",
              description="A 2xx follows the publisher confirm and nothing else (rule 8). Steady state ~1,333/s across types; 13,333/s is configured headroom (DL-104)."),
        panel(0, "Accepted / s by door", [_target('sum by (source) (rate(records_accepted_total[5m]))', "{{source}}")], "reqps",
              description="internal = Heimdal (/internal/stats); external = everyone else. The external door binds nothing to the record's tenant until T-068 (DL-151 residual)."),
        panel(0, "Envelope rejected (400) / s by type", [
            _target('sum by (type) (rate(records_validation_failed_total[5m]))', "validation_failed {{type}}"),
            _target('sum by (type) (rate(records_clock_skew_rejected_total[5m]))', "clock_skew {{type}}")], "reqps",
              description="Envelope failures only — a PAYLOAD failure is the Writer's dead-letter, never a 400 (ADR-007 §5)."),
        panel(0, "Not accepted after the broker (503) / s", [
            _target('sum by (type) (rate(records_publish_failed_total[5m]))', "publish_failed {{type}}"),
            _target('sum by (type) (rate(records_unroutable_total[5m]))', "unroutable {{type}}")], "reqps",
              description="503 is the backpressure channel and is the mechanism working, not an incident (Q13c). unroutable means acked by the broker and wanted by no queue: a binding is missing."),
    ]),
    ("HTTP — what Heimdal sees", [
        panel(0, "Responses / s by status", [_target('sum by (status) (rate(http_server_requests_seconds_count{job="stats-input", uri=~"/(internal|external)/stats"}[5m]))', "{{status}}")], "reqps"),
        panel(0, "Mean and max request time (includes the publisher confirm)", [
            _target('sum (rate(http_server_requests_seconds_sum{job="stats-input", uri=~"/(internal|external)/stats"}[5m])) / sum (rate(http_server_requests_seconds_count{job="stats-input", uri=~"/(internal|external)/stats"}[5m]))', "mean"),
            _target('max (http_server_requests_seconds_max{job="stats-input", uri=~"/(internal|external)/stats"})', "max")], "s",
              description="Confirm latency, not throughput, is what turns a burst into 503s (T-048)."),
        panel(0, "Input replicas up", [_target('sum(up{job="stats-input"})', "replicas")], "short", kind="stat", thresholds=OK_GREEN_RED),
    ]),
    ("Q13b — do gateways jitter across the 10-minute cycle? (DL-104; read for the first 24 h)", [
        panel(0, "ts.server mod 600 — share of records per 30 s slot", [_target('sum by (le) (increase(records_ts_server_cycle_offset_seconds_bucket[1h]))', "{{le}}")], "short", kind="heatmap", w=24, h=10,
              description="Flat = jitter confirmed, 13,333/s stays headroom. A spike in one slot = synchronised bursts, and hardware is not sized down."),
        panel(0, "Passthrough members seen / s", [
            _target('sum by (type) (rate(records_converter_ts_present_total[5m]))', "ts.converter {{type}}"),
            _target('sum by (type) (rate(records_ts_device_corrected_total[5m]))', "meta.tsDeviceCorrected {{type}}")], "reqps",
              description="ts.converter on ~100% is expected (ADR-007 §2). tsDeviceCorrected is Q15f's data point."),
    ]),
])

# -------------------------------------------------------------- PostgreSQL --
postgres = layout([
    ("WAL archiver — the RPO ≤ 5 min half of ADR-003 D5 (T-051, DL-155)", [
        panel(0, "Archive failures / 5m", [_target('increase(pg_stat_archiver_failed_count[5m])', "{{instance}}")], "short",
              thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 1}],
              description="The archiver retries a failing archive_command forever and reports it nowhere else. A root-owned volume and a full disk are the two known causes."),
        panel(0, "Seconds since the last archived WAL segment", [_target('pg_stat_archiver_last_archive_age', "{{instance}}")], "s",
              thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 600}, {"color": "red", "value": 900}],
              description="archive_timeout=300 ships a segment at least every five minutes; over 900 s the recovery point has stopped moving."),
        panel(0, "archive_mode on", [_target('pg_settings_archive_mode', "{{instance}}")], "short", kind="stat", thresholds=OK_GREEN_RED),
        panel(0, "Exporter can reach PostgreSQL", [_target('pg_up', "{{instance}}")], "short", kind="stat", thresholds=OK_GREEN_RED,
              description="Every panel on this dashboard is blind while this is 0."),
    ]),
])

DASHBOARDS = {
    "stats-app-writer.json": dashboard("stats-app-writer", "Stats App — Writer",
        "stats-writer: throughput, errors, latency, queues, retention, readiness. Owner: Mukesh (module), Naman (retention and queue rows). Series contract: docs/contracts/writer-metrics-v1.md.",
        writer, ["stats-app", "writer"]),
    "stats-app-input.json": dashboard("stats-app-input", "Stats App — Input",
        "stats-input: the two ingest doors, HTTP outcomes, publisher-confirm latency, the Q13b histogram. Owner: Mukesh.",
        ingest, ["stats-app", "input"]),
    "stats-app-postgres.json": dashboard("stats-app-postgres", "Stats App — PostgreSQL",
        "PostgreSQL WAL archiver (RPO) via postgres_exporter. Owner: Naman.",
        postgres, ["stats-app", "postgres"]),
}


def render():
    return {name: json.dumps(d, indent=2, sort_keys=True) + "\n" for name, d in DASHBOARDS.items()}


if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    for name, text in render().items():
        (OUT / name).write_text(text, encoding="utf-8")
        print(f"wrote {OUT / name}")
    sys.exit(0)
