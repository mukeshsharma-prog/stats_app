#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
PROJECT=stats-app-bundle
docker compose -p "$PROJECT" --env-file "$ROOT/.env" -f "$ROOT/docker/docker-compose.yml" --profile apps down
