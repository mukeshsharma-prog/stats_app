#!/usr/bin/env bash
# T-049 — replay drill: 2,667/s of ALREADY-STORED records on top of live traffic (NFR-04;
# ADR-005; Q13c / DL-109).
#
# What it proves, and what it cannot. Heimdal agreed a 2× nominal replay cap (2,667/s) after
# an outage, and we cannot enforce it. This drill proves OUR behaviour under a replay at that
# cap: every replayed record is a duplicate by definition and ON CONFLICT (ts_device, uid)
# DO NOTHING absorbs it, metered as records_duplicate_suppressed and never alerted on
# (ADR-005 says so); nothing dead-letters; and no alert that was not already active fires.
# It proves nothing about whether Heimdal honours the cap (DL-109).
#
# Three phases, one type (the replay corpus is one fixture):
#   1  HISTORY  live rate for HISTORY_SECS, bodies saved byte for byte; wait for the queue
#               to drain so every record is a row before anything replays it
#   2  REPLAY   REPLAY_RATE/s of those same bodies (cycled) on top of LIVE_RATE/s of new
#               unique records for REPLAY_SECS -- 4,000/s in total, which is the shipped
#               per-instance rate limit (DL-121), so a few 429s are the limiter clipping at
#               its own cap and are Heimdal's to retry; 503 is the confirm path saying no,
#               which is the backpressure channel working, not an incident (DL-109)
#   3  DRAIN    wait for the queue to drain; then assert from the Writer's own counters
#
# Assertions (each printed PASS/FAIL; exit 1 on any FAIL):
#   A  duplicate_suppressed delta == replay 200s   every accepted replay was absorbed
#   B  persisted delta == history 200s + live 200s  every NEW record landed, exactly once
#   C  dead_lettered delta == 0, write_failed delta == 0, dlq_depth unchanged
#   D  no alert active after the drain that was not active before it started (Prometheus,
#      if reachable on :9090 -- the Compose `observability` profile); the oldest-message
#      age the queue reached is reported against the 300 s warning threshold
#
# Usage: ops/drills/t049-replay.sh [http://host:port/internal/stats]
#   LIVE_RATE=1333 REPLAY_RATE=2667 HISTORY_SECS=30 REPLAY_SECS=60 override the shape.
#   Records carry unique uids and fresh timestamps: run this against a database you are
#   willing to fill (~1333 × HISTORY_SECS + 1333 × REPLAY_SECS rows).
set -u
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
URL=${1:-http://localhost:8081/internal/stats}
WRITER=${WRITER:-http://localhost:8082/actuator/prometheus}
PROM=${PROM:-http://localhost:9090}
LIVE_RATE=${LIVE_RATE:-1333}
REPLAY_RATE=${REPLAY_RATE:-2667}
HISTORY_SECS=${HISTORY_SECS:-30}
REPLAY_SECS=${REPLAY_SECS:-60}
TYPE=${TYPE:-lorastats}
OUT=${OUT:-/tmp/t049-$(date +%Y%m%d-%H%M%S)}
mkdir -p "$OUT"
hr() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
fail=0
check() { # check NAME COND DETAIL
  if [ "$2" = 1 ]; then printf '  \033[32mPASS\033[0m %s -- %s\n' "$1" "$3"; else printf '  \033[31mFAIL\033[0m %s -- %s\n' "$1" "$3"; fail=1; fi
}
# The Writer's own counters, by exported name; an absent first-event series reads 0.
writer() { curl -sf "$WRITER" | awk -v m="$1{type=\"$TYPE\"}" '$1==m {print $2; f=1} END {if (!f) print 0}'; }
status200() { python3 -c "import json,sys; print(json.load(open('$1'))['statuses'].get('200',0))"; }
queue_depth() { writer queue_depth; }
oldest_age() { writer queue_oldest_message_age_seconds; }
alerts() { curl -sf "$PROM/api/v1/alerts" 2>/dev/null | python3 -c "
import json,sys
try: d=json.load(sys.stdin)['data']['alerts']
except Exception: sys.exit(0)
for a in sorted(set((a['labels']['alertname'], a['labels'].get('type',''), a['state']) for a in d)): print('/'.join(a))"; }
drain() { # wait for queue_depth == 0 (polled every 30 s by the Writer, DL-137); track the oldest age seen
  local max=0 age depth t0=$SECONDS
  while :; do
    depth=$(queue_depth); age=$(oldest_age)
    [ "$age" != NaN ] && age=${age%.*} && [ "${age:-0}" -gt "$max" ] && max=$age
    printf '  t=%3ds  queue_depth=%s  oldest_message_age=%ss\n' $((SECONDS - t0)) "$depth" "$age"
    [ "${depth%.*}" = 0 ] && [ $((SECONDS - t0)) -ge 35 ] && break   # one full poll interval at zero
    [ $((SECONDS - t0)) -gt 900 ] && { echo "  gave up waiting for the drain after 900 s"; break; }
    sleep 10
  done
  echo "$max"
}

hr "0. Preconditions"
java -version 2>&1 | head -1
curl -sf "${URL%/internal/stats}/actuator/prometheus" >/dev/null || { echo "Input at $URL is not reachable"; exit 2; }
curl -sf "$WRITER" >/dev/null || { echo "Writer metrics at $WRITER are not reachable"; exit 2; }
if curl -sf "$PROM/-/ready" >/dev/null; then PROM_OK=1; else PROM_OK=0; echo "  Prometheus not reachable at $PROM -- assertion D is skipped (start the observability profile)"; fi
echo "  results in $OUT"
echo "  shape: history $LIVE_RATE/s × ${HISTORY_SECS}s, then replay $REPLAY_RATE/s + live $LIVE_RATE/s × ${REPLAY_SECS}s, type $TYPE"
DUP0=$(writer records_duplicate_suppressed_total); PERS0=$(writer records_persisted_total)
DEAD0=$(writer records_dead_lettered_total); WF0=$(writer records_write_failed_total); DLQ0=$(writer dlq_depth)
[ "$PROM_OK" = 1 ] && alerts > "$OUT/alerts-before.txt" && echo "  alerts active before: $(wc -l < "$OUT/alerts-before.txt" | tr -d ' ')"

hr "1. HISTORY — $LIVE_RATE/s for ${HISTORY_SECS}s, bodies saved"
# --warmup 0: a warm-up's records are new rows that no phase counts, and would break assertion B.
java "$REPO/ops/load/BurstLoad.java" --url "$URL" --rate "$LIVE_RATE" --duration "$HISTORY_SECS" --warmup 0 \
  --save-bodies "$OUT/history.rs" --json "$OUT/1-history.json" | grep -v '   t='
echo "  waiting for the queue to drain so every history record is a row before it is replayed"
drain > /dev/null
PERS1=$(writer records_persisted_total)

hr "2. REPLAY — $REPLAY_RATE/s of stored bodies + $LIVE_RATE/s live, for ${REPLAY_SECS}s"
java "$REPO/ops/load/BurstLoad.java" --url "$URL" --rate "$REPLAY_RATE" --duration "$REPLAY_SECS" \
  --replay "$OUT/history.rs" --json "$OUT/2-replay.json" > "$OUT/2-replay.log" 2>&1 &
RP=$!
java "$REPO/ops/load/BurstLoad.java" --url "$URL" --rate "$LIVE_RATE" --duration "$REPLAY_SECS" --warmup 0 \
  --json "$OUT/2-live.json" > "$OUT/2-live.log" 2>&1 &
LP=$!
# sample the queue while both run
( while kill -0 $RP 2>/dev/null; do printf '  t=%3ds  queue_depth=%s  oldest_message_age=%ss\n' "$SECONDS" "$(queue_depth)" "$(oldest_age)"; sleep 10; done ) &
wait $RP; wait $LP; wait
grep -E 'RESULT|statuses|503s|publisher-confirm' "$OUT/2-replay.log" "$OUT/2-live.log" | sed 's|^.*/2-||'

hr "3. DRAIN, then assert"
MAXAGE=$(drain | tee /dev/stderr | tail -1)
DUP1=$(writer records_duplicate_suppressed_total); PERS2=$(writer records_persisted_total)
DEAD1=$(writer records_dead_lettered_total); WF1=$(writer records_write_failed_total); DLQ1=$(writer dlq_depth)
H200=$(status200 "$OUT/1-history.json"); R200=$(status200 "$OUT/2-replay.json"); L200=$(status200 "$OUT/2-live.json")
d() { python3 -c "print(int(float('$1') - float('$2')))"; }
DUPD=$(d "$DUP1" "$DUP0"); PERSD=$(d "$PERS2" "$PERS0"); DEADD=$(d "$DEAD1" "$DEAD0"); WFD=$(d "$WF1" "$WF0")
echo
echo "  history 200s=$H200   replay 200s=$R200   live 200s=$L200"
check "A duplicate_suppressed == replay 200s" "$([ "$DUPD" -eq "$R200" ] && echo 1 || echo 0)" "delta $DUPD vs $R200 replayed and accepted"
check "B persisted == history + live 200s" "$([ "$PERSD" -eq $((H200 + L200)) ] && echo 1 || echo 0)" "delta $PERSD vs $((H200 + L200)) new records accepted"
check "C nothing dead-lettered or failed" "$([ "$DEADD" -eq 0 ] && [ "$WFD" -eq 0 ] && [ "${DLQ1%.*}" = "${DLQ0%.*}" ] && echo 1 || echo 0)" "dead_lettered +$DEADD, write_failed +$WFD, dlq_depth $DLQ0 -> $DLQ1"
if [ "$PROM_OK" = 1 ]; then
  alerts > "$OUT/alerts-after.txt"
  NEW=$(comm -13 "$OUT/alerts-before.txt" "$OUT/alerts-after.txt")
  check "D no new alert" "$([ -z "$NEW" ] && echo 1 || echo 0)" "${NEW:-none} (active before: $(tr '\n' ' ' < "$OUT/alerts-before.txt"))"
fi
echo "  oldest-message age peaked at ${MAXAGE}s during the replay (StatsAppMainQueueAging warns at 300 s for 5 m)"
echo
[ "$fail" = 0 ] && echo "T-049: PASS. Results in $OUT" || echo "T-049: FAIL. Results in $OUT"
exit $fail
