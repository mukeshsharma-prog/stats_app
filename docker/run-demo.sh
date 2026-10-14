#!/usr/bin/env sh
# Run docker/demo.sh — ten steps, each naming the rule it proves — against this bundle's
# stack, from inside the bundled tools container (DL-171). Needs nothing but Docker.
#
# The demo is real: step 5 leaves one record in app_statsstore.lorastats.dlq, and
# step 10 STOPS RabbitMQ for a few seconds to show a 503, then starts it again. Run
# ./docker/run-postman.sh BEFORE this, not after — its "DLQs stayed empty" check will
# (correctly) fail once the demo has dead-lettered a record.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
COMPOSE="$ROOT/docker/docker-compose.yml"
PROJECT=stats-app-bundle

if [ ! -f "$ROOT/.env" ]; then
  echo "Missing .env. Run ./docker/start-stats-app.sh first." >&2
  exit 1
fi

tty_flag=
[ -t 0 ] && [ -t 1 ] || tty_flag=-T
# shellcheck disable=SC2086 # tty_flag is empty or one word
exec docker compose -p "$PROJECT" --env-file "$ROOT/.env" -f "$COMPOSE" --profile apps --profile tools \
  run --rm $tty_flag stats-tools demo
