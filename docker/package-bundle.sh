#!/usr/bin/env sh
# Build one offline, shareable archive containing the complete Stats App stack.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
VERSION=${1:-$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || date +%Y%m%d)}
DIST="$ROOT/dist"
STAGE=$(mktemp -d)
PAYLOAD="$STAGE/stats-app-docker-bundle-$VERSION"
ARCHIVE="$DIST/stats-app-docker-bundle-$VERSION.tar.gz"
trap 'rm -rf "$STAGE"' EXIT INT TERM

if [ ! -f "$ROOT/.env" ]; then
  echo "Missing .env. Copy .env.example to .env and fill it before packaging." >&2
  exit 1
fi

if rg -Fq '${env.' "$ROOT/docs/setup/keycloak-realm-statsapp.json"; then
  echo "Invalid Keycloak environment placeholder namespace in realm template." >&2
  exit 1
fi
jq empty "$ROOT/docs/setup/keycloak-realm-statsapp.json"
mkdir -p "$DIST" "$PAYLOAD/docker/postgres-init" "$PAYLOAD/docs/setup" "$PAYLOAD/postman"

printf 'Building the three application images...\n'
docker compose --env-file "$ROOT/.env" -f "$ROOT/docker/docker-compose.yml" --profile apps build \
  stats-input stats-writer stats-api

printf 'Pulling pinned infrastructure and test-runner images...\n'
docker pull postgres:17-alpine
docker pull rabbitmq:4-management-alpine
docker pull quay.io/keycloak/keycloak:26.0
docker pull postman/newman:alpine
docker pull prom/prometheus:v3.5.0
docker pull quay.io/prometheuscommunity/postgres-exporter:v0.17.1
docker pull grafana/grafana:11.6.0

cp "$ROOT/docker/docker-compose.yml" "$PAYLOAD/docker/"
cp "$ROOT/docker/start-stats-app.sh" "$ROOT/docker/stop-stats-app.sh" "$ROOT/docker/run-postman.sh" "$ROOT/docker/check-monitoring.sh" "$PAYLOAD/docker/"
mkdir -p "$PAYLOAD/ops"
cp -R "$ROOT/ops/prometheus" "$ROOT/ops/postgres-exporter" "$ROOT/ops/grafana" "$PAYLOAD/ops/"
cp "$ROOT/docker/postgres-init/10-read-only-user.sql" "$PAYLOAD/docker/postgres-init/"
cp "$ROOT/docs/setup/keycloak-realm-statsapp.json" "$PAYLOAD/docs/setup/"
cp "$ROOT/postman/stats-app.postman_collection.json" "$ROOT/postman/local.postman_environment.json" "$ROOT/postman/README.md" "$PAYLOAD/postman/"
cp "$ROOT/docker/BUNDLE.md" "$PAYLOAD/README.md"

printf 'Exporting Docker images (this is the large part of the bundle)...\n'
docker save -o "$PAYLOAD/stats-app-images.tar" \
  stats-app/stats-input:dev \
  stats-app/stats-writer:dev \
  stats-app/stats-api:dev \
  postgres:17-alpine \
  rabbitmq:4-management-alpine \
  quay.io/keycloak/keycloak:26.0 \
  prom/prometheus:v3.5.0 \
  quay.io/prometheuscommunity/postgres-exporter:v0.17.1 \
  grafana/grafana:11.6.0 \
  postman/newman:alpine

printf 'Creating %s...\n' "$ARCHIVE"
tar -C "$STAGE" -czf "$ARCHIVE" "$(basename "$PAYLOAD")"
(cd "$DIST" && sha256sum "$(basename "$ARCHIVE")" > "$(basename "$ARCHIVE").sha256")
printf 'Bundle ready:\n  %s\n  %s.sha256\n' "$ARCHIVE" "$ARCHIVE"
