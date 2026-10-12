#!/usr/bin/env sh
# Wait for actual successful scrapes, not just a running Prometheus process.
set -eu

PROMETHEUS_CONTAINER=${PROMETHEUS_CONTAINER:-stats-prometheus}
MONITORING_TIMEOUT_SECONDS=${MONITORING_TIMEOUT_SECONDS:-120}
case "$MONITORING_TIMEOUT_SECONDS" in
  ''|*[!0-9]*) echo 'MONITORING_TIMEOUT_SECONDS must be a positive integer.' >&2; exit 1 ;;
esac
[ "$MONITORING_TIMEOUT_SECONDS" -gt 0 ] || exit 1

query='count(up{job=~"stats-input|stats-writer|stats-api|postgres"}) == 4 and min(up{job=~"stats-input|stats-writer|stats-api|postgres"}) == 1 and min(pg_up{job="postgres"}) == 1'
deadline=$(( $(date +%s) + MONITORING_TIMEOUT_SECONDS ))
printf 'Waiting for all four Prometheus targets'
while :; do
  result=$(docker exec "$PROMETHEUS_CONTAINER" /bin/promtool query instant http://localhost:9090 "$query" 2>/dev/null || true)
  if printf '%s\n' "$result" | grep -q '=> 4 @'; then
    printf ' UP (Input, Writer, API, PostgreSQL)\n'
    exit 0
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    printf ' timed out\n' >&2
    docker exec "$PROMETHEUS_CONTAINER" /bin/promtool query instant http://localhost:9090 up >&2 || true
    docker exec "$PROMETHEUS_CONTAINER" /bin/promtool query instant http://localhost:9090 pg_up >&2 || true
    echo 'Inspect http://localhost:9090/targets for scrape errors.' >&2
    exit 1
  fi
  printf '.'
  sleep 3
done
