#!/usr/bin/env bash
# Stats App — end-to-end demo against the local compose stack.
#
#   docker compose --profile apps up -d --build      # from docker/, wait for (healthy)
#   ./docker/demo.sh                                 # from anywhere
#
# Ten steps, each naming the rule it proves (CLAUDE.md §5). Every number printed is live:
# the Input response, the Writer's Prometheus counters, the rows in PostgreSQL, the
# broker's queue depths. Nothing here is mocked, and the negative steps are real —
# step 10 stops RabbitMQ.
#
# Two local gotchas this script works around on purpose (DEVELOPMENT.md §3.2):
#   * psql goes through `docker exec "$POSTGRES_CONTAINER"` — a host PostgreSQL on 5432 would
#     otherwise shadow the container's port mapping.
#   * .env is read key by key, not `source`d — an unquoted value with a "(" aborts a
#     `source` in bash and zsh, and everything after it stays unexported.
set -u
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FX="$REPO/docs/schemas/fixtures"
# Heimdal's door. /external/stats is the same handler for callers that are not Heimdal (DL-151).
INPUT_BASE_URL=${INPUT_BASE_URL:-http://localhost:8081}
WRITER_BASE_URL=${WRITER_BASE_URL:-http://localhost:8082}
IN="$INPUT_BASE_URL/internal/stats"
WR="$WRITER_BASE_URL/actuator/prometheus"
API=${API_BASE_URL:-http://localhost:8083}
KC=${KEYCLOAK_BASE_URL:-http://localhost:8080}
DEMO_ENV_FILE=${DEMO_ENV_FILE:-$REPO/.env}
POSTGRES_CONTAINER=${POSTGRES_CONTAINER:-stats-postgres}
RABBITMQ_CONTAINER=${RABBITMQ_CONTAINER:-stats-rabbitmq}
WRITER_CONTAINER=${WRITER_CONTAINER:-stats-writer}
BODY="$(mktemp)"; trap 'rm -f "$BODY"' EXIT

envv() { grep -E "^$1=" "$DEMO_ENV_FILE" | head -1 | cut -d= -f2- | sed -E "s/^'(.*)'$/\1/"; }
for v in STATS_NO_ROLE_PASSWORD STATS_NO_TENANT_PASSWORD STATS_ALPHA_READER_PASSWORD; do export "$v=$(envv "$v")"; done

hr()     { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
psq()    { docker exec "$POSTGRES_CONTAINER" psql -U stats -d statsdb -Atc "$1"; }
metric() { curl -s "$WR" | grep -E "^$1" || echo "(no series yet for $1)"; }
dlq()    { docker exec "$RABBITMQ_CONTAINER" rabbitmqctl list_queues name messages 2>/dev/null | awk -v q="app_statsstore.$1.dlq" '$1==q{print $2}'; }
# Fixtures carry $expect/$comment markers for the CI gate (validate-fixtures.py); the
# wire record is the fixture without them.
rec()    { jq -c 'del(.["$expect"], .["$expectCode"], .["$comment"])' "$1"; }
# The valid fixtures are production records dated 27 Aug 2026 and age a day every day.
# For the happy path they are re-stamped to NOW, which is what live traffic looks like;
# for the DL-149 step, to a device time three weeks ago. Edge fixtures (clock skew, late
# arrival) keep their own timestamps because the timestamp IS the test.
NOW=$(date +%s)
send() { # send <json-on-stdin>  -> "HTTP <code>   X-Trace-Id: ..." then the body if any
  local out; out=$(curl -s -D - -o "$BODY" -X POST "$IN" -H 'Content-Type: application/json' --data-binary @-)
  printf 'HTTP %s   %s\n' "$(echo "$out" | head -1 | awk '{print $2}')" "$(echo "$out" | grep -i '^x-trace-id' | tr -d '\r')"
  [ -s "$BODY" ] && jq -c . "$BODY" 2>/dev/null; : > "$BODY"
}
post()    { rec "$1" | send; }
postnow() { rec "$1" | jq -c --argjson now "$NOW" '.ts.server=$now | .ts.device=$now | .ts.datastore=$now | .ts.converter=$now' | send; }
postold() { rec "$1" | jq -c --argjson now "$NOW" --argjson d "$2" '.uid=(.uid+"-late-"+($d|tostring)) | .ts.server=$now | .ts.device=($now - $d*86400) | .ts.datastore=$now | .ts.converter=$now' | send; }

hr "0. Three modules and three infrastructure services are up"
for base in "$INPUT_BASE_URL" "$WRITER_BASE_URL" "$API"; do printf '  %s %s\n' "$base" "$(curl -s "$base/actuator/health" | jq -r .status)"; done
docker ps --format '  {{.Names}}: {{.Status}}' | grep -E 'stats-(postgres|rabbitmq|keycloak)'
echo "  partitioned tables: $(psq "select string_agg(relname, ', ' order by relname) from pg_class where relkind='p'")"
echo "  lorastats partitions: $(psq "select min(relname)||' .. '||max(relname)||' ('||count(*)||')' from pg_class where relname ~ '^lorastats_[0-9_]+$'")"

hr "1. Happy path — all five stat types, including LTE degraded states and network interfaces. 2xx ONLY after publisher confirm (rule 8)"
postnow "$FX/valid/sysstats-production-minimal.json"
postnow "$FX/valid/omsstats-production.json"          # ts.converter on every record: stored in passthrough, no normalisation (ADR-004)
postnow "$FX/valid/lorastats-production-canonical.json"
postnow "$FX/valid/lorastats-dialect-prefixed.json"     # dialect C -> the SAME row shape (DL-127)
postnow "$FX/valid/ltestats-live-2026-10-09.json"
postnow "$FX/valid/ltestats-production-2g-no-rsrp.json"
postnow "$FX/valid/ltestats-production-unknown-technology.json"
postnow "$FX/valid/netstats-live-2026-10-09.json"
postnow "$FX/valid/netstats-production-wwan-down.json"
postnow "$FX/valid/netstats-extra-interface.json"
sleep 3
echo "  new rows: $(psq "select 'ltestats='||count(*) from ltestats") $(psq "select 'netstats='||count(*) from netstats")"
echo "  rows:  $(psq "select 'sysstats='||count(*) from sysstats")  $(psq "select 'omsstats='||count(*) from omsstats")  $(psq "select 'lorastats='||count(*) from lorastats")"
psq "select type, clsbox, tenant, to_char(ts_device,'YYYY-MM-DD HH24:MI:SS') ts_device, tableoid::regclass partition from (select 'sysstats' type, clsbox, tenant, ts_device, tableoid from sysstats union all select 'omsstats', clsbox, tenant, ts_device, tableoid from omsstats union all select 'lorastats', clsbox, tenant, ts_device, tableoid from lorastats) t order by 1, 4 desc limit 8" | sed 's/^/  /'
psq "select 'omsstats passthrough: '||(passthrough->'ts')::text from omsstats order by ts_device desc limit 1" | sed 's/^/  /'
metric 'records_persisted_total'
metric 'records_lorastats_dialect_total'

hr "2. A gateway back from THREE WEEKS offline — inside the window, before this deployment existed (DL-149)"
postold "$FX/valid/lorastats-production-canonical.json" 21
sleep 3
psq "select clsbox, to_char(ts_device,'YYYY-MM-DD') ts_device, tableoid::regclass partition from lorastats where uid like '%-late-21' order by ts_device desc limit 1" | sed 's/^/  stored in: /'
echo "  lorastats DLQ depth: $(dlq lorastats)   (before DL-149 this record was here, permanent, SQLSTATE 23514)"

hr "3. Redelivery is harmless — the same record again -> 2xx, ON CONFLICT DO NOTHING, metered not silent (rule 13)"
postnow "$FX/valid/sysstats-production-minimal.json"
sleep 2
metric 'records_duplicate_suppressed_total'
echo "  sysstats rows still: $(psq 'select count(*) from sysstats')"

hr "4. ENVELOPE failure -> 400 at Input, nothing published (rule 7, gate 1)"
post "$FX/edge/sysstats-clock-skew-future.json"        # ts.device > ts.server + 5 min (ADR-003 D9)
post "$FX/invalid/envelope-missing-tenant.json"
post "$FX/invalid/envelope-ts-in-milliseconds.json"    # seconds, not milliseconds (trap)
echo '{not json' | curl -s -o /dev/null -w '  malformed JSON -> HTTP %{http_code}\n' -X POST "$IN" -H 'Content-Type: application/json' --data-binary @-

hr "5. PAYLOAD failure -> accepted 2xx, then DEAD-LETTERED by the Writer on the FIRST attempt (rule 7 gate 2, DL-126)"
before=$(dlq lorastats)
post "$FX/invalid/lorastats-dialect-unknown.json"      # a fourth dialect: the enumeration is closed at three
sleep 6
echo "  lorastats DLQ depth before: $before  after: $(dlq lorastats)   (tiers skipped: attempt=1, permanent=true)"
metric 'records_payload_rejected_total'
docker logs "$WRITER_CONTAINER" --since 30s 2>&1 | grep '"event":"payload_rejected"' | tail -1 | jq -c '{event,reason,statType,uid,traceId}' | sed 's/^/  writer log: /'

hr "6. Late arrival -> 2xx, SKIPPED, metered, ACKed — never dead-lettered (rule 11, D5b, DL-133)"
post "$FX/edge/sysstats-late-arrival.json"             # ts.device past the 90-day window
sleep 3
metric 'records_expired_on_arrival_total'
docker logs "$WRITER_CONTAINER" --since 30s 2>&1 | grep '"event":"expired_on_arrival"' | tail -1 | jq -c '{event,statType,uid,ageDays,retentionDays}' | sed 's/^/  writer log: /'
echo "  sysstats rows still: $(psq 'select count(*) from sysstats')   sysstats DLQ depth: $(dlq sysstats)"

hr "7. Non-OK upstream status -> stored + metered + WARN, never dead-lettered (DL-128)"
postnow "$FX/valid/lorastats-status-not-ok.json"
sleep 2
metric 'records_upstream_status_not_ok_total'

hr "8. What ops watches (T-027 / T-050) — polled and cached; a failed poll is NaN, never zero"
curl -s "$WR" | grep -E '^(queue_depth|dlq_depth|queue_oldest_message_age_seconds|consumer_availability|broker_metrics_collection_success)' | sed 's/^/  /'

hr "9. Security at both ends: stats_ro cannot write (rule 2, PostgreSQL 42501); the API is 401 without a token (ADR-006)"
docker exec "$POSTGRES_CONTAINER" psql -U stats_ro -d statsdb -c "insert into sysstats (uid) values ('x')" 2>&1 | sed 's/^/  /'
tok() { curl -s -X POST "$KC/realms/statsapp/protocol/openid-connect/token" -d client_id=stats-dev-cli -d grant_type=password -d "username=$1" -d "password=$2" | jq -r .access_token; }
printf '  anonymous     -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' "$API/v1/stats/lorastats")"
token=$(tok "$(envv STATS_ALPHA_READER_USERNAME)" "$STATS_ALPHA_READER_PASSWORD")
for type in lorastats sysstats omsstats ltestats netstats; do
  printf '  alpha-reader %s -> %s\n' "$type" \
    "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" "$API/v1/stats/$type?from=$((NOW-120))&to=$((NOW+1))")"
done

hr "10. Broker down -> 503, NOT 200 (a 200 is a promise). Then the broker comes back."
docker stop "$RABBITMQ_CONTAINER" >/dev/null; sleep 2
postnow "$FX/valid/sysstats-production-minimal.json"
postnow "$FX/valid/ltestats-live-2026-10-09.json"
postnow "$FX/valid/netstats-live-2026-10-09.json"
docker start "$RABBITMQ_CONTAINER" >/dev/null
printf '  waiting for RabbitMQ'
recovery_deadline=$((SECONDS+90))
until docker exec "$RABBITMQ_CONTAINER" rabbitmq-diagnostics -q check_running >/dev/null 2>&1; do
  [[ $SECONDS -lt $recovery_deadline ]] || { echo ' broker recovery timed out'; exit 1; }
  printf '.'; sleep 2
done
# Docker DNS can retain a negative lookup after a stopped broker. Wait for Input's
# actual broker health rather than assuming a fixed sleep means publishing works.
until [[ $(curl --max-time 10 -s "$INPUT_BASE_URL/actuator/health" | jq -r .status) == UP ]]; do
  [[ $SECONDS -lt $recovery_deadline ]] || { echo ' Input recovery timed out'; exit 1; }
  printf '.'; sleep 2
done
echo
postnow "$FX/valid/sysstats-production-minimal.json"
postnow "$FX/valid/ltestats-live-2026-10-09.json"
postnow "$FX/valid/netstats-live-2026-10-09.json"

# Inside the bundle's tools container $WR is a compose-network name; say where to look from the host.
echo; echo "Done.  RabbitMQ UI http://localhost:15672   Keycloak http://localhost:8080   Writer metrics ${DEMO_HOST_WRITER_METRICS_URL:-$WR}"
