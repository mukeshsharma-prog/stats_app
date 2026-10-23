#!/usr/bin/env bash
# T-048 — burst load against stats-input at the 13,333/s design headroom (NFR-04, Q13a).
#
# Three runs, because one number answers the wrong question:
#   A  4,000/s   the shipped per-instance rate limit (DL-121): the confirm p99 at the ceiling
#   B  13,333/s  against that limit: the 429 backpressure that shields the confirm path
#   C  13,333/s  with the limit lifted for the run: the confirm p99 the task asks for, and
#                where a single instance actually saturates
#
# Runs A and B need only a running Input; run C needs one started with
# STATS_INPUT_LIMITS_MAX_REQUESTS_PER_SECOND raised (the script tells you how) and is
# skipped otherwise. Records carry unique uids and fresh timestamps, so the Writer stores
# every one of them: run this against a database you are willing to fill.
#
# Usage: ops/drills/t048-burst-load.sh [http://host:port/internal/stats] [seconds]
set -u
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
URL=${1:-http://localhost:8081/internal/stats}
SECS=${2:-20}
OUT=${OUT:-/tmp/t048-$(date +%Y%m%d-%H%M%S)}
mkdir -p "$OUT"
hr() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
limit() { curl -s "${URL%/internal/stats}/actuator/prometheus" >/dev/null 2>&1 && echo reachable || echo unreachable; }

hr "0. Preconditions"
java -version 2>&1 | head -1
[ "$(limit)" = reachable ] || { echo "Input at $URL is not reachable"; exit 2; }
echo "  results in $OUT"

hr "A. 4,000/s for $SECS s — the per-instance ceiling (DL-121)"
java "$REPO/ops/load/BurstLoad.java" --url "$URL" --rate 4000 --duration "$SECS" --warmup 5 --json "$OUT/A-4000.json" | grep -v '^\[T-048\]   t='

hr "B. 13,333/s for $SECS s against the 4,000/s limit — expect 429s, no 503s"
java "$REPO/ops/load/BurstLoad.java" --url "$URL" --rate 13333 --duration "$SECS" --warmup 2 --json "$OUT/B-13333-limited.json" | grep -v '^\[T-048\]   t='

hr "C. 13,333/s with the limit lifted — the confirm p99 at the headroom"
if grep -q '"429": [1-9]' "$OUT/B-13333-limited.json"; then
  cat <<MSG
  Skipped: this Input still rate-limits at 4,000/s. For run C start it with
    STATS_INPUT_LIMITS_MAX_REQUESTS_PER_SECOND=20000
  (a dev-only setting for this measurement, never a production value: the limiter and
  Tomcat's 200 request threads are sized together -- see docs/runbooks/load-test-burst.md)
  and re-run:  java ops/load/BurstLoad.java --url $URL --rate 13333 --duration $SECS
MSG
else
  java "$REPO/ops/load/BurstLoad.java" --url "$URL" --rate 13333 --duration "$SECS" --warmup 3 --json "$OUT/C-13333-unlimited.json" | grep -v '^\[T-048\]   t='
fi
echo; echo "Done. Compare with the numbers recorded in docs/runbooks/load-test-burst.md."
