#!/usr/bin/env python3
"""CI gate for the Grafana dashboards and the Prometheus rule files (T-056, DL-158).

Three checks, each for a failure that is silent in Grafana:

  1. The committed JSON is exactly what build-dashboards.py generates -- a hand edit to
     the JSON is lost on the next generation, and a generator edit without regeneration
     ships a stale dashboard.
  2. Every metric name in every panel expression and every alert rule is a series
     something actually exports: an application series whose base name is a string
     literal in stats-writer or stats-input main source (with Micrometer's Prometheus
     suffixes), or one of the named infrastructure series below. A panel over a series
     that does not exist renders as an empty graph, indistinguishable from "nothing
     happened", which is the one thing a dashboard must never say by accident.
  3. No broker or retention GAUGE is aggregated with sum (DL-137). Each Writer replica
     polls the same shared queue; summing N replicas reports N times the backlog.

Exit code is non-zero on any failure, so CI can gate on it.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
RULES = REPO / "ops" / "prometheus"
import importlib.util  # noqa: E402

_spec = importlib.util.spec_from_file_location("build_dashboards", HERE / "build-dashboards.py")
build_dashboards = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(build_dashboards)

# Series exported by something other than this project's code, by name.
INFRASTRUCTURE_SERIES = {
    # Prometheus itself
    "up",
    # Micrometer / Spring Boot built-ins
    "hikaricp_connections_active", "jvm_memory_used_bytes", "process_uptime_seconds",
    "http_server_requests_seconds_count", "http_server_requests_seconds_sum", "http_server_requests_seconds_max",
    # postgres_exporter: three from the built-in pg_stat_archiver collector, verified on a
    # live scrape on 22 Sep 2026 (DL-160) -- and pg_settings_archive_mode, which the built-in
    # settings collector does NOT export (archive_mode is an enum). It exists only because
    # ops/postgres-exporter/queries.yml defines it; keep the name in step with that file.
    "pg_up", "pg_stat_archiver_failed_count", "pg_stat_archiver_last_archive_age", "pg_settings_archive_mode",
}

# Application series whose name is composed at runtime, so the literal scan cannot see
# them. Each entry says where the composition is; a new entry is a reviewed decision.
COMPOSED_APPLICATION_SERIES = {
    "records_lorastats_dialect": "LorastatsBatchWriter.DIALECT_METRIC = \"records_\" + wireName + \"_dialect\" (DL-127)",
}

# Gauges that describe ONE shared resource observed by every replica (DL-137, DL-156).
SHARED_GAUGES = {
    "queue_depth", "dlq_depth", "consumer_availability", "queue_oldest_message_age_seconds",
    "broker_metrics_collection_success", "broker_metrics_last_success_timestamp_seconds",
    "retention_oldest_partition_age_days", "retention_last_sweep_timestamp_seconds", "retention_period_days",
}

PROMQL_WORDS = {
    "sum", "max", "min", "avg", "count", "rate", "increase", "irate", "by", "without", "on", "ignoring",
    "group_left", "group_right", "and", "or", "unless", "time", "histogram_quantile", "vector", "absent",
    "le", "bool", "offset", "instance", "type", "status", "job", "source", "reason", "tier", "dialect", "uri",
}
METRIC_TOKEN = re.compile(r"(?<![\w\"'])([a-z][a-z0-9_]*)(?=\s*(?:\{|\[|\)|$|\s|/|\*|-|\+|>|<|!|=|,))")
SUM_OF_GAUGE = re.compile(r"\bsum\s*(?:by\s*\([^)]*\)|without\s*\([^)]*\))?\s*\(\s*(?:rate\s*\(|increase\s*\(|max\s*\(|min\s*\()?\s*(" + "|".join(sorted(SHARED_GAUGES)) + r")\b")


def application_base_names():
    literal = re.compile(r'"((?:records|queue|dlq|consumer|broker|retention)_[a-z0-9_]+)"')
    names = set(COMPOSED_APPLICATION_SERIES)
    for module in ("stats-writer", "stats-input"):
        for path in (REPO / module / "src" / "main" / "java").rglob("*.java"):
            names.update(literal.findall(path.read_text(encoding="utf-8")))
    return names


def is_known(metric, bases):
    if metric in INFRASTRUCTURE_SERIES:
        return True
    for base in bases:
        if metric == base or metric == base + "_total":
            return True
        if metric.startswith(base + "_seconds_") or metric == base + "_seconds":
            return True
        if metric.endswith(("_bucket", "_count", "_sum", "_max")) and metric.rsplit("_", 1)[0] == base:
            return True
    return False


def metrics_in(expr):
    tokens = set(METRIC_TOKEN.findall(expr)) - PROMQL_WORDS
    # A metric name in this project always carries an underscore -- except `up`.
    return {t for t in tokens if "_" in t or t == "up"}


def expressions():
    """(where, expr) for every dashboard panel target and every alert rule."""
    out = []
    for name, dashboard in build_dashboards.DASHBOARDS.items():
        for panel in dashboard["panels"]:
            for target in panel.get("targets", []):
                out.append((f"{name} › {panel['title']}", target["expr"]))
    for rules in sorted(RULES.glob("*-alerts.yml")):
        text = rules.read_text(encoding="utf-8")
        # expr: either a one-line value or a >- block; collect until the next key
        for match in re.finditer(r"^\s*expr:\s*(>-?\s*)?\n?((?:.*\n)*?)(?=^\s*(?:for|labels|annotations|record|alert):)", text, re.M):
            out.append((rules.name, " ".join(match.group(2).split())))
        for match in re.finditer(r"^\s*expr:\s*([^>\n][^\n]*)$", text, re.M):
            out.append((rules.name, match.group(1).strip()))
    return out


def main():
    failures = []

    generated = build_dashboards.render()
    for name, text in generated.items():
        path = HERE / "dashboards" / name
        if not path.exists() or path.read_text(encoding="utf-8") != text:
            failures.append(f"{path.relative_to(REPO)} is not what build-dashboards.py generates; run it and commit the result")

    bases = application_base_names()
    exprs = expressions()
    seen = 0
    for where, expr in exprs:
        for metric in sorted(metrics_in(expr)):
            seen += 1
            if not is_known(metric, bases):
                failures.append(f"{where}: `{metric}` is not a series this project or a named exporter exports")
        for hit in SUM_OF_GAUGE.finditer(expr):
            failures.append(f"{where}: sum over the shared gauge `{hit.group(1)}` — aggregate replicas with max (DL-137)")

    print(f"{len(generated)} dashboards, {len(exprs)} expressions, {seen} metric references checked "
          f"against {len(bases)} application series and {len(INFRASTRUCTURE_SERIES)} infrastructure series")
    for failure in failures:
        print(f"FAIL {failure}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
