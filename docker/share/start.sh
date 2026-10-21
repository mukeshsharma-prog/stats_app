#!/usr/bin/env bash
# Stats App — start the share bundle, moving any host port that is already taken (DL-164).
#
#   ./start.sh                   # the stack: six services, returns when all are healthy
#   ./start.sh demo              # the stack, then the end-to-end demo
#   ./start.sh observability     # the stack plus Prometheus / Grafana
#
# Before `docker compose up`, every PORT_* in .env is probed on 127.0.0.1. A port that
# something else on this machine already holds — a Homebrew PostgreSQL on 5432 is the
# usual one — is moved to the next free port, with a WARNING, and the new value is
# written back to .env. It has to be written back: every later `docker compose …`
# command reads .env too, and one that saw the old port would recreate the container
# on it and fail exactly as before.
#
# A port this bundle's own running containers already publish is kept, so running this
# twice does not move anything.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

PROJECT=stats-app-demo
ENV_FILE=.env
MAX_TRIES=100

# Preserve the old container-local H2 database before Compose attaches a new volume.
if [ "$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' stats-demo-keycloak 2>/dev/null || true)" = "$PROJECT" ] &&
   [ -z "$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/opt/keycloak/data"}}{{.Type}}{{end}}{{end}}' stats-demo-keycloak 2>/dev/null || true)" ]; then
  echo "Preserve existing users: ./migrate-keycloak-data.sh stats-demo-keycloak" >&2
  exit 1
fi

# PORT_* variable, default, label. Order is the order they are probed and claimed.
PORTS=(
  "PORT_POSTGRES:5433:PostgreSQL"
  "PORT_RABBITMQ_UI:15672:RabbitMQ UI"
  "PORT_KEYCLOAK:8080:Keycloak"
  "PORT_INPUT:8081:stats-input"
  "PORT_WRITER:8082:stats-writer"
  "PORT_API:8083:stats-api"
  "PORT_PROMETHEUS:9090:Prometheus"
  "PORT_GRAFANA:3000:Grafana"
)

warn() { printf '\033[1;33mWARNING:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

[ -f "$ENV_FILE" ] || die "$ENV_FILE not found — run this from the unpacked bundle directory."
command -v docker >/dev/null || die "docker is not on PATH."
docker info >/dev/null 2>&1 || die "Docker is not running."

env_get() { grep -E "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2- || true; }

env_set() {
  local tmp; tmp="$(mktemp "${ENV_FILE}.XXXXXX")"
  if grep -qE "^$1=" "$ENV_FILE"; then
    awk -v k="$1" -v v="$2" 'index($0, k "=") == 1 { print k "=" v; next } { print }' "$ENV_FILE" > "$tmp"
  else
    { cat "$ENV_FILE"; echo "$1=$2"; } > "$tmp"
  fi
  cat "$tmp" > "$ENV_FILE"; rm -f "$tmp"   # keep .env's own inode and permissions
}

# Published by this bundle's own running containers — those are ours, not a conflict.
OURS="$(docker ps --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Ports}}' 2>/dev/null || true)"
ours() { printf '%s' "$OURS" | grep -qE "(^|[ ,])127\.0\.0\.1:$1->"; }

# Something on this machine listens on the port. Two probes, because neither is complete
# alone: a connect finds any listener reachable on 127.0.0.1 (including one bound to
# 0.0.0.0, which is what collides with Docker's bind), and lsof, where it exists, also
# finds one on ::1 or another interface.
in_use() {
  local p="$1"
  if (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then return 0; fi
  if command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then return 0; fi
  return 1
}

CLAIMED=" "
claimed() { case "$CLAIMED" in *" $1 "*) return 0 ;; esac; return 1; }

for entry in "${PORTS[@]}"; do
  IFS=: read -r var def label <<<"$entry"
  want="$(env_get "$var")"
  if ! [[ "$want" =~ ^[0-9]+$ ]] || [ "$want" -lt 1 ] || [ "$want" -gt 65535 ]; then
    [ -n "$want" ] && warn "$var='$want' in $ENV_FILE is not a port number — using $def."
    want="$def"
  fi

  port="$want"
  if ! ours "$port"; then
    tries=0
    while claimed "$port" || in_use "$port"; do
      tries=$((tries + 1))
      [ "$tries" -le "$MAX_TRIES" ] || die "no free port for $label in $want..$((want + MAX_TRIES)). Set $var in $ENV_FILE by hand."
      port=$((port + 1))
      [ "$port" -le 65535 ] || die "no free port for $label above $want. Set $var in $ENV_FILE by hand."
    done
  fi
  CLAIMED="$CLAIMED$port "

  if [ "$port" != "$want" ]; then
    warn "port $want ($label) is already in use on this machine — using $port instead. Saved as $var=$port in $ENV_FILE."
  fi
  [ "$(env_get "$var")" = "$port" ] || env_set "$var" "$port"
  printf -v "$var" '%s' "$port"
done

case "${1:-}" in
  "")            docker compose up -d --wait ;;
  demo)          docker compose up -d --wait && docker compose run --rm demo ;;
  observability) docker compose --profile observability up -d --wait ;;
  *)             die "unknown argument '$1' — use: ./start.sh [demo|observability]" ;;
esac

cat <<EOF

Stats App is up. Ports on this machine (127.0.0.1 only):
  Ingest     http://localhost:$PORT_INPUT/internal/stats
  Read API   http://localhost:$PORT_API/v1/stats/{type}
  Writer     http://localhost:$PORT_WRITER/actuator/prometheus
  Keycloak   http://localhost:$PORT_KEYCLOAK
  RabbitMQ   http://localhost:$PORT_RABBITMQ_UI
  PostgreSQL localhost:$PORT_POSTGRES   (or: docker exec -it stats-demo-postgres psql -U stats -d statsdb)
EOF
if [ "${1:-}" = observability ]; then
  echo "  Grafana    http://localhost:$PORT_GRAFANA   Prometheus http://localhost:$PORT_PROMETHEUS"
fi
