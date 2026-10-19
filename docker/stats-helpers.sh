# shellcheck shell=sh
# Helpers for calling the Stats App by hand — the walkthrough in MANUAL.md uses them.
#
#   cd <bundle directory>          # the one holding .env and docker/
#   . docker/stats-helpers.sh      # bash or zsh; needs curl and jq on this machine
#
# No curl or jq? `./docker/shell.sh` opens a shell in the bundled tools container with
# these helpers already loaded, and every MANUAL.md command works there unchanged.
#
# Sourced, not executed: it defines functions and variables in YOUR shell. .env itself
# cannot be sourced (a value with "(" in it aborts `source`), so values are read from
# it one key at a time.
#
#   env_get KEY        a value from .env
#   tok USER [--prompt]  an access token for any Keycloak user; optional fixture
#                        credentials are read from .env
#   claims TOKEN       the claims every read is filtered on: tenant, aud, roles
#   pg SQL             run SQL in the bundle's PostgreSQL (docker exec, as the writer role)
#   NOW                Unix seconds, captured when this file is sourced
#   INPUT WRITER API KC RABBIT   base URLs of the services

STATS_ENV_FILE=${STATS_ENV_FILE:-$PWD/.env}

if [ ! -f "$STATS_ENV_FILE" ]; then
  echo "stats-helpers: no .env in $PWD." >&2
  echo "  cd into the bundle directory (the one with .env and docker/), run" >&2
  echo "  ./docker/start-stats-app.sh once, then source this file again." >&2
  # shellcheck disable=SC2317 # `exit` is for the case where this file was executed
  return 1 2>/dev/null || exit 1
fi
for _stats_tool in curl jq; do
  command -v "$_stats_tool" >/dev/null 2>&1 || {
    echo "stats-helpers: $_stats_tool is not installed. Use ./docker/shell.sh instead —" >&2
    echo "  it has curl and jq and loads these helpers for you." >&2
    # shellcheck disable=SC2317
    return 1 2>/dev/null || exit 1
  }
done
unset _stats_tool

INPUT=${INPUT_BASE_URL:-http://localhost:8081}
WRITER=${WRITER_BASE_URL:-http://localhost:8082}
API=${API_BASE_URL:-http://localhost:8083}
KC=${KEYCLOAK_BASE_URL:-http://localhost:8080}
RABBIT=${RABBITMQ_API_URL:-http://localhost:15672}
NOW=$(date +%s)

env_get() { grep -E "^$1=" "$STATS_ENV_FILE" | head -1 | cut -d= -f2-; }

# Use the same token flow in the host shell and the bundled tools container.
tok() {
  STATS_ENV_FILE="$STATS_ENV_FILE" KEYCLOAK_BASE_URL="$KC" \
    "${STATS_TOKEN_SCRIPT:-${STATS_ENV_FILE%/*}/docker/token.sh}" "$@"
}

claims() {
  _stats_p=$(printf %s "$1" | cut -d. -f2 | tr '_-' '/+')
  while [ $(( ${#_stats_p} % 4 )) -ne 0 ]; do _stats_p="$_stats_p="; done
  printf %s "$_stats_p" | base64 -d | jq '{tenant, aud, roles: .realm_access.roles}'
  unset _stats_p
}

pg() { docker exec "${POSTGRES_CONTAINER:-stats-postgres}" psql -U "$(env_get POSTGRES_USER)" -d "$(env_get POSTGRES_DB)" "$@"; }

# On the host, say so when the Keycloak answering on :8080 belongs to another directory —
# the usual reason `tok` is refused. Skipped inside the tools container, where the paths
# are the container's.
if [ -z "${STATS_TOOLS_CONTAINER:-}" ] && command -v docker >/dev/null 2>&1; then
  _stats_kc_dir=$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' stats-keycloak 2>/dev/null || true)
  _stats_here=$(cd "$(dirname "$STATS_ENV_FILE")" && pwd -P)/docker
  if [ -n "$_stats_kc_dir" ] && [ "$(cd "$_stats_kc_dir" 2>/dev/null && pwd -P)" != "$_stats_here" ]; then
    echo "stats-helpers: WARNING — the running Keycloak was started from $_stats_kc_dir," >&2
    echo "  not from this directory. Its passwords are in that directory's .env, so tok will be refused here." >&2
  fi
  unset _stats_kc_dir _stats_here
fi

echo "Stats App helpers loaded: env_get tok claims pg; INPUT=$INPUT API=$API NOW=$NOW"
