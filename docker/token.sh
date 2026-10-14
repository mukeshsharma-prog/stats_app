#!/usr/bin/env sh
# Print a Keycloak access token for one of the bundle's five test users — for pasting
# into Postman (Authorization → Bearer Token) or a curl command (DL-171).
#
#   ./docker/token.sh alpha-reader             the token
#   ./docker/token.sh beta-reader --claims     what is inside it: tenant, aud, roles
#
# Users: alpha-reader, beta-reader, no-role, no-tenant, empty-tenant.
# Reads the password from THIS bundle's .env, whatever directory you run it from.
# Needs only curl; jq is used to pretty-print --claims when it is installed.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ENV_FILE=${STATS_ENV_FILE:-$ROOT/.env}
KC=${KEYCLOAK_BASE_URL:-http://localhost:8080}

usage() { echo "usage: $0 alpha-reader|beta-reader|no-role|no-tenant|empty-tenant [--claims]" >&2; exit 2; }
[ $# -ge 1 ] || usage
user=$1
case "${2:-}" in ''|--claims) ;; *) usage ;; esac

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing $ENV_FILE. Run ./docker/start-stats-app.sh first." >&2
  exit 1
fi
env_get() { grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2-; }

var="STATS_$(printf %s "$user" | tr 'a-z-' 'A-Z_')_PASSWORD"
password=$(env_get "$var")
[ -n "$password" ] || { echo "No $var in $ENV_FILE — is '$user' one of the five test users?" >&2; usage; }

resp=$(curl -s -X POST "$KC/realms/$(env_get STATS_REALM_NAME)/protocol/openid-connect/token" \
  -d "client_id=$(env_get STATS_DEV_CLI_CLIENT_ID)" -d grant_type=password \
  --data-urlencode "username=$user" --data-urlencode "password=$password") || {
  echo "Keycloak is not reachable at $KC. Is the stack running? (./docker/start-stats-app.sh)" >&2
  exit 1
}
token=$(printf %s "$resp" | sed -n 's/.*"access_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
if [ -z "$token" ]; then
  echo "Keycloak did not issue a token for $user: $resp" >&2
  echo "'Invalid user credentials' means the Keycloak on $KC was started from a different" >&2
  echo "bundle directory, whose .env holds different passwords." >&2
  exit 1
fi

if [ "${2:-}" != --claims ]; then
  printf '%s\n' "$token"
  exit 0
fi

# A JWT is base64url without padding; base64 -d needs the padding restored.
payload=$(printf %s "$token" | cut -d. -f2 | tr '_-' '/+')
while [ $(( ${#payload} % 4 )) -ne 0 ]; do payload="$payload="; done
if command -v jq >/dev/null 2>&1; then
  printf %s "$payload" | base64 -d | jq '{tenant, aud, roles: .realm_access.roles}'
else
  printf %s "$payload" | base64 -d
  echo
fi
