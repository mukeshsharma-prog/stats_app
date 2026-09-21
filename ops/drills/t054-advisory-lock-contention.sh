#!/usr/bin/env bash
# T-054 — advisory-lock contention between two REAL Writer processes (ADR-003 D3, STA-23C).
#
# Starts two extra Writer containers from the stack's own image, both serving lorastats,
# both with the maintenance cron firing at the top of every minute, and lets them collide
# for two ticks against the running Compose PostgreSQL. Then reads what each one logged.
#
# What a pass looks like, per tick, per replica: pre-creation was either ensured or
# skipped ("advisory lock is held", DEBUG), retention was either applied / a no-op or
# skipped -- and NEVER failed. Afterwards no advisory lock is held by anyone and no
# backend of either replica is idle in transaction. Two replicas sharing the lock key is
# the production default (D8 runs N replicas per type), so this is the ordinary case.
#
# The deterministic, in-build half of this drill is
# stats-writer/src/test/java/.../retention/AdvisoryLockContentionIntegrationTest.java --
# two pools in one JVM, which is what the SESSION-level lock actually distinguishes. This
# script is the literal two-process reading, against the real image and the real stack.
#
# Prerequisites: `docker compose --profile apps up -d` is running (the image
# stats-app/stats-writer:dev exists and stats-postgres is healthy). Both drill replicas
# also consume app_statsstore.lorastats for the minutes they run; run it on an idle stack.
set -u
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
IMAGE=stats-app/stats-writer:dev
NET=stats-app_default
TICKS=${TICKS:-2}
envv() { grep -E "^$1=" "$REPO/.env" | head -1 | cut -d= -f2- | sed -E "s/^'(.*)'$/\1/"; }
PGU=$(envv POSTGRES_USER); PGD=$(envv POSTGRES_DB)
psq() { docker exec stats-postgres psql -U "$PGU" -d "$PGD" -tAc "$1"; }
hr() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
cleanup() { docker rm -f t054-writer-a t054-writer-b >/dev/null 2>&1; }
trap cleanup EXIT

hr "0. Preconditions"
docker image inspect "$IMAGE" >/dev/null || { echo "image $IMAGE missing: docker compose --profile apps build"; exit 2; }
docker inspect -f '{{.State.Health.Status}}' stats-postgres | grep -q healthy || { echo "stats-postgres is not healthy"; exit 2; }
echo "  advisory locks held before: $(psq "select count(*) from pg_locks where locktype='advisory'")"
echo "  lorastats partitions before: $(psq "select count(*) from pg_inherits where inhparent='lorastats'::regclass")"

hr "1. Two replicas, one lock key, cron at the top of every minute"
for r in a b; do
  docker run -d --name "t054-writer-$r" --network "$NET" --env-file "$REPO/.env" \
    -e STAT_TYPE=lorastats \
    -e POSTGRES_RO_USER= -e POSTGRES_RO_PASSWORD= \
    -e STATS_RETENTION_SCHEDULE='0 * * * * *' \
    -e STATS_RETENTION_STAGGER=PT0S \
    -e SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_APPLICATIONNAME="t054-writer-$r" \
    -e LOGGING_LEVEL_COM_ZENNER_DATAHUB_STATS_WRITER_RETENTION=DEBUG \
    "$IMAGE" >/dev/null
done
for r in a b; do
  printf '  waiting for t054-writer-%s' "$r"
  until docker exec "t054-writer-$r" curl -fsS http://localhost:8082/actuator/health/readiness >/dev/null 2>&1; do printf '.'; sleep 2; done; echo " ready"
done

hr "2. Letting them collide for $TICKS tick(s)"
START=$(date +%s)
# wait past the next $TICKS minute boundaries, plus a few seconds for the runs to finish
for i in $(seq 1 "$TICKS"); do
  now=$(date +%s); sleep $(( 60 - now % 60 + 5 ))
  echo "  tick $i passed ($(date -u +%H:%M:%S)Z)"
done

hr "3. What each replica logged (statType=lorastats, since the drill started)"
report() { # report <replica>
  local log; log=$(docker logs "t054-writer-$1" 2>&1 | grep '"statType":"lorastats"' | grep -v '"trigger":"startup"')
  local ens skp app nop rsk fail
  ens=$(grep -c '"event":"partitions_ensured"'            <<<"$log"); skp=$(grep -c '"event":"partition_precreation_skipped"' <<<"$log")
  app=$(grep -c '"event":"retention_applied"'             <<<"$log"); nop=$(grep -c '"event":"retention_no_op"'               <<<"$log")
  rsk=$(grep -c '"event":"retention_skipped"'             <<<"$log")
  fail=$(grep -Ec '"event":"(retention_failed|partition_precreation_failed)"' <<<"$log")
  printf '  %-14s pre-create: ensured=%d skipped=%d | retention: applied=%d no-op=%d skipped=%d | FAILED=%d\n' "$1" "$ens" "$skp" "$app" "$nop" "$rsk" "$fail"
  echo "$ens $skp $app $nop $rsk $fail"
}
A=$(report a | tail -1); B=$(report b | tail -1)
report a | head -1; report b | head -1
set -- $A; aE=$1 aS=$2 aA=$3 aN=$4 aR=$5 aF=$6
set -- $B; bE=$1 bS=$2 bA=$3 bN=$4 bR=$5 bF=$6

hr "4. Verdict"
rc=0
check() { if [ "$1" -eq 0 ]; then printf '  \033[32mPASS\033[0m %s\n' "$2"; else printf '  \033[31mFAIL\033[0m %s\n' "$2"; rc=1; fi; }
check $(( aF + bF )) "no replica logged a failed pre-creation or retention"
check $(( (aE + aS) < TICKS || (bE + bS) < TICKS )) "each replica made a pre-creation decision (ensured or skipped) on every tick"
check $(( (aA + aN + aR) < TICKS || (bA + bN + bR) < TICKS )) "each replica made a retention decision (applied, no-op or skipped) on every tick"
check $(( aS + bS + aR + bR == 0 )) "at least one replica actually lost the race and skipped (the collision was real)"
check "$(psq "select count(*) from pg_locks where locktype='advisory'")" "no advisory lock is held after the drill"
check "$(psq "select count(*) from pg_stat_activity where application_name like 't054-writer-%' and state like 'idle in transaction%'")" "no drill backend is idle in transaction"
for r in a b; do
  check "$(docker logs "t054-writer-$r" 2>&1 | grep '"statType":"lorastats"' | grep -c '"level":"ERROR"')" "t054-writer-$r logged no ERROR for lorastats maintenance"
done
echo "  lorastats partitions after: $(psq "select count(*) from pg_inherits where inhparent='lorastats'::regclass")  (elapsed $(( $(date +%s) - START )) s)"
exit $rc
