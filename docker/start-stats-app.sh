#!/usr/bin/env sh
# Start the complete distributable Stats App stack.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
COMPOSE="$ROOT/docker/docker-compose.yml"
PROJECT=stats-app-bundle
ENV_FILE="$ROOT/.env"
IMAGE_ARCHIVE="$ROOT/stats-app-images.tar"

random_hex() {
  od -An -N "$1" -tx1 /dev/urandom | tr -d ' \n'
}

write_env() {
  umask 077
  admin_password=$(random_hex 18)
  api_secret=$(random_hex 24)
  input_secret=$(random_hex 24)
  alpha_password=$(random_hex 18)
  beta_password=$(random_hex 18)
  no_role_password=$(random_hex 18)
  no_tenant_password=$(random_hex 18)
  empty_tenant_password=$(random_hex 18)
  cursor_key=$(random_hex 48)
  postgres_password=$(random_hex 24)
  postgres_ro_password=$(random_hex 24)
  rabbit_password=$(random_hex 24)

  cat > "$ENV_FILE" <<EOF
STATS_REALM_NAME=statsapp
STATS_REALM_DISPLAY_NAME=Datahub Core - Stats App (local bundle)
STATS_API_CLIENT_ID=stats-api
STATS_INPUT_CLIENT_ID=stats-input
STATS_DEV_CLI_CLIENT_ID=stats-dev-cli
STATS_READ_ONLY_ROLE=stats.readOnly
STATS_INGEST_ROLE=stats.ingest
STATS_API_AUDIENCE=stats-api
STATS_TENANT_CLAIM=tenant
KEYCLOAK_ISSUER=http://localhost:8080/realms/statsapp
KEYCLOAK_JWK_SET_URI=
KC_BOOTSTRAP_ADMIN_USERNAME=admin
KC_BOOTSTRAP_ADMIN_PASSWORD=$admin_password
STATS_INPUT_AUTH_MODE=none
STATS_API_CLIENT_SECRET=$api_secret
STATS_INPUT_CLIENT_SECRET=$input_secret
STATS_ALPHA_READER_USERNAME=alpha-reader
STATS_BETA_READER_USERNAME=beta-reader
STATS_NO_ROLE_USERNAME=no-role
STATS_NO_TENANT_USERNAME=no-tenant
STATS_EMPTY_TENANT_USERNAME=empty-tenant
STATS_USER_EMAIL_DOMAIN=stats.local
STATS_ALPHA_READER_PASSWORD=$alpha_password
STATS_BETA_READER_PASSWORD=$beta_password
STATS_NO_ROLE_PASSWORD=$no_role_password
STATS_NO_TENANT_PASSWORD=$no_tenant_password
STATS_EMPTY_TENANT_PASSWORD=$empty_tenant_password
STATS_TENANT_A_ID=tenant-alpha
STATS_TENANT_B_ID=tenant-beta
STAT_TYPE=all
STATS_API_CURSOR_SIGNING_KEY=$cursor_key
POSTGRES_URL=jdbc:postgresql://postgres:5432/statsdb
POSTGRES_DB=statsdb
POSTGRES_RO_USER=stats_ro
POSTGRES_RO_PASSWORD=$postgres_ro_password
POSTGRES_USER=stats
POSTGRES_PASSWORD=$postgres_password
RABBITMQ_HOST=rabbitmq
RABBITMQ_PORT=5672
RABBITMQ_USER=stats
RABBITMQ_PASSWORD=$rabbit_password
RABBITMQ_DEFAULT_USER=stats
RABBITMQ_DEFAULT_PASS=$rabbit_password
EOF
  chmod 600 "$ENV_FILE"
  printf 'Generated local credentials in %s (mode 0600).\n' "$ENV_FILE"
}

command -v docker >/dev/null 2>&1 || {
  echo "Docker is required but was not found." >&2
  exit 1
}
docker info >/dev/null 2>&1 || {
  echo "Docker is installed but its daemon is not reachable." >&2
  exit 1
}

if [ ! -f "$ENV_FILE" ]; then
  write_env
fi

missing_image=false
for image in \
  stats-app/stats-input:dev \
  stats-app/stats-writer:dev \
  stats-app/stats-api:dev \
  postgres:17-alpine \
  rabbitmq:4-management-alpine \
  quay.io/keycloak/keycloak:26.0 \
  postman/newman:alpine
do
  docker image inspect "$image" >/dev/null 2>&1 || missing_image=true
done

if [ -f "$IMAGE_ARCHIVE" ] && [ "$missing_image" = true ]; then
  printf 'Loading bundled Docker images...\n'
  docker load -i "$IMAGE_ARCHIVE"
fi

printf 'Starting PostgreSQL, RabbitMQ, Keycloak, Input, Writer, and API...\n'
docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f "$COMPOSE" --profile apps up -d --no-build

deadline=$(( $(date +%s) + 300 ))
for container in stats-postgres stats-rabbitmq stats-keycloak stats-input stats-writer stats-api; do
  printf 'Waiting for %-16s' "$container"
  while :; do
    status=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container" 2>/dev/null || true)
    if [ "$status" = "healthy" ]; then
      printf ' healthy\n'
      break
    fi
    if [ "$status" = "unhealthy" ] || [ "$status" = "exited" ] || [ "$status" = "dead" ]; then
      printf ' %s\n' "$status" >&2
      docker logs --tail 80 "$container" >&2 || true
      exit 1
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      printf ' timed out (last status: %s)\n' "${status:-missing}" >&2
      docker logs --tail 80 "$container" >&2 || true
      exit 1
    fi
    printf '.'
    sleep 3
  done
done

cat <<'EOF'

Stats App is ready. Open these service pages in your browser:
  Input:       http://localhost:8081/
  Writer:      http://localhost:8082/

Readiness checks:
  Input:       http://localhost:8081/actuator/health/readiness
  Writer:      http://localhost:8082/actuator/health/readiness
  Read API:    http://localhost:8083/actuator/health/readiness

Browser consoles:
  Keycloak:    http://localhost:8080
  RabbitMQ UI: http://localhost:15672

Use Postman for API requests:
  Ingest:      POST http://localhost:8081/internal/stats
  Read base:   http://localhost:8083/v1/stats (requires a bearer token)

Run the complete Postman suite with:
  ./docker/run-postman.sh

This bundle uses unauthenticated Input mode for local evaluation. Do not expose it
on a shared or production network until inbound authentication is configured.
EOF
