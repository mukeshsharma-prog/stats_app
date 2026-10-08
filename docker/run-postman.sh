#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
COMPOSE="$ROOT/docker/docker-compose.yml"
PROJECT=stats-app-bundle

if [ ! -f "$ROOT/.env" ]; then
  echo "Missing .env. Run ./docker/start-stats-app.sh first." >&2
  exit 1
fi

docker compose -p "$PROJECT" --env-file "$ROOT/.env" -f "$COMPOSE" --profile apps --profile test run --rm postman-tests
