#!/usr/bin/env sh
# Open a shell in the bundled tools container with the MANUAL.md helpers loaded
# (env_get, tok, claims, pg, $INPUT, $API, ...). Needs nothing on this machine but
# Docker: curl, jq and the Docker CLI are inside the container (DL-171).
#
#   ./docker/shell.sh                       interactive
#   ./docker/shell.sh bash -c '. /tools/docker/stats-helpers.sh && tok alpha-reader'
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
  run --rm $tty_flag stats-tools "${@:-shell}"
