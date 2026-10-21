#!/usr/bin/env sh
# Build one offline, shareable archive containing the complete Stats App stack.
#
#   ./docker/package-bundle.sh [version]                    # amd64 + arm64 (default)
#   PLATFORMS=linux/arm64 ./docker/package-bundle.sh        # one architecture only
#   BUNDLE_README=/path/to/README.md ./docker/package-bundle.sh
#   BUILDX_FLAGS='--add-host host:1.2.3.4' ./docker/package-bundle.sh   # extra build flags
#
# BUILDX_FLAGS exists for build-network workarounds: the Gradle wrapper (Java) tries
# only the FIRST address DNS returns for its download host, so one unreachable CDN
# edge fails every build that curl would have survived (13 Oct 2026, DL-170).
#
# Each platform gets its own single-platform image archive,
# stats-app-images-<arch>.tar, and start-stats-app.sh loads the one matching the
# receiving Docker engine. One archive per platform rather than one multi-platform
# archive, because a multi-platform `docker load` depends on the receiver's image
# store; a single-platform archive loads on any of them.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
VERSION=${1:-$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || date +%Y%m%d)}
PLATFORMS=${PLATFORMS:-linux/amd64,linux/arm64}
DIST="$ROOT/dist"
BUNDLE_README=${BUNDLE_README:-$ROOT/docker/BUNDLE.md}
STAGE=$(mktemp -d)
PAYLOAD="$STAGE/stats-app-docker-bundle-$VERSION"
ARCHIVE="$DIST/stats-app-docker-bundle-$VERSION.tar.gz"
trap 'rm -rf "$STAGE"' EXIT INT TERM

APP_MODULES="stats-input stats-writer stats-api"
INFRA_IMAGES="postgres:17-alpine
rabbitmq:4-management-alpine
quay.io/keycloak/keycloak:26.0
postman/newman:alpine
prom/prometheus:v3.5.0
quay.io/prometheuscommunity/postgres-exporter:v0.17.1
grafana/grafana:11.6.0"

if [ ! -r "$BUNDLE_README" ]; then
  echo "Bundle README is not readable: $BUNDLE_README" >&2
  exit 1
fi

if [ ! -f "$ROOT/.env" ]; then
  echo "Missing .env. Copy .env.example to .env and fill it before packaging." >&2
  exit 1
fi

if grep -Fq '${env.' "$ROOT/docs/setup/keycloak-realm-statsapp.json"; then
  echo "Invalid Keycloak environment placeholder namespace in realm template." >&2
  exit 1
fi
jq empty "$ROOT/docs/setup/keycloak-realm-statsapp.json"
mkdir -p "$DIST" "$PAYLOAD/docker/postgres-init" "$PAYLOAD/docs/setup" "$PAYLOAD/postman"

printf 'Building the three application images for %s...\n' "$PLATFORMS"
for module in $APP_MODULES; do
  # shellcheck disable=SC2086 # BUILDX_FLAGS is deliberately word-split
  docker buildx build ${BUILDX_FLAGS:-} --platform "$PLATFORMS" -f "$ROOT/docker/Dockerfile" \
    --build-arg MODULE="$module" -t "stats-app/$module:dev" --load "$ROOT"
done
# The hand-run tools image: bash, curl, jq, a Docker CLI, demo.sh and its fixtures, so
# MANUAL.md and the demo need nothing on the receiving machine but Docker (DL-171).
# shellcheck disable=SC2086
docker buildx build ${BUILDX_FLAGS:-} --platform "$PLATFORMS" -f "$ROOT/docker/Dockerfile.tools" \
  -t stats-app/stats-tools:dev --load "$ROOT"

printf 'Pulling pinned infrastructure and test-runner images for %s...\n' "$PLATFORMS"
for image in $INFRA_IMAGES; do
  for platform in $(printf '%s' "$PLATFORMS" | tr ',' ' '); do
    docker pull -q --platform "$platform" "$image"
  done
done

cp "$ROOT/docker/docker-compose.yml" "$PAYLOAD/docker/"
cp "$ROOT/docker/start-stats-app.sh" "$ROOT/docker/stop-stats-app.sh" "$ROOT/docker/run-postman.sh" "$ROOT/docker/check-monitoring.sh" "$PAYLOAD/docker/"
cp "$ROOT/docker/stats-helpers.sh" "$ROOT/docker/shell.sh" "$ROOT/docker/token.sh" "$ROOT/docker/migrate-keycloak-data.sh" "$ROOT/docker/check-zra-isolation.py" "$ROOT/docker/run-demo.sh" "$PAYLOAD/docker/"
mkdir -p "$PAYLOAD/ops"
cp -R "$ROOT/docs/runbooks" "$PAYLOAD/docs/"
cp -R "$ROOT/ops/prometheus" "$ROOT/ops/postgres-exporter" "$ROOT/ops/grafana" "$PAYLOAD/ops/"
cp "$ROOT/docker/postgres-init/10-read-only-user.sql" "$PAYLOAD/docker/postgres-init/"
mkdir -p "$PAYLOAD/docs/schemas/fixtures/valid"
cp "$ROOT/docs/schemas/fixtures/valid/lorastats-production-canonical.json" "$PAYLOAD/docs/schemas/fixtures/valid/"
cp "$ROOT/docs/setup/keycloak-realm-statsapp.json" "$ROOT/docs/setup/keycloak-user-profile.json" "$ROOT/docs/setup/keycloak-realm-statsapp.md" "$PAYLOAD/docs/setup/"
cp "$ROOT/postman/stats-app.postman_collection.json" "$ROOT/postman/local.postman_environment.json" "$ROOT/postman/README.md" "$PAYLOAD/postman/"
cp "$BUNDLE_README" "$PAYLOAD/README.md"
cp "$ROOT/docker/BUNDLE.md" "$PAYLOAD/BUNDLE.md"
cp "$ROOT/docker/MANUAL.md" "$PAYLOAD/MANUAL.md"
# Interpreter caches from running ops/ scripts locally are not part of the bundle.
find "$PAYLOAD" -name __pycache__ -type d -prune -exec rm -rf {} +

printf 'Exporting Docker images (this is the large part of the bundle)...\n'
for platform in $(printf '%s' "$PLATFORMS" | tr ',' ' '); do
  arch=${platform#linux/}
  printf '  %s\n' "$platform"
  # shellcheck disable=SC2086 # word-splitting the image lists is intended
  docker save --platform "$platform" -o "$PAYLOAD/stats-app-images-$arch.tar" \
    $(for module in $APP_MODULES; do printf 'stats-app/%s:dev ' "$module"; done) stats-app/stats-tools:dev \
    $INFRA_IMAGES
done

printf 'Creating %s...\n' "$ARCHIVE"
tar -C "$STAGE" -czf "$ARCHIVE" "$(basename "$PAYLOAD")"
(cd "$DIST" && sha256sum "$(basename "$ARCHIVE")" > "$(basename "$ARCHIVE").sha256")
printf 'Bundle ready:\n  %s\n  %s.sha256\n' "$ARCHIVE" "$ARCHIVE"
