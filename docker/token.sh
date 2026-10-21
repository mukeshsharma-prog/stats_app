#!/usr/bin/env sh
# Obtain a token for any Keycloak user. Fixture passwords remain an optional fallback.
# --prompt ignores .env credentials; --password-stdin supports noninteractive callers.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ENV_FILE=${STATS_ENV_FILE:-$ROOT/.env}
KC=${KEYCLOAK_BASE_URL:-http://localhost:8080}
usage() { echo "usage: $0 USER [--claims] [--prompt|--password-stdin]" >&2; exit 2; }
[ $# -ge 1 ] || usage
user=$1; shift
mode=auto; show_claims=false
for arg in "$@"; do
  case "$arg" in
    --claims) show_claims=true ;;
    --prompt|--password-stdin) [ "$mode" = auto ] || usage; mode=$arg ;;
    *) usage ;;
  esac
done
for tool in curl jq; do command -v "$tool" >/dev/null 2>&1 || { echo "$tool is required." >&2; exit 1; }; done
env_get() { if [ -f "$ENV_FILE" ]; then awk -v k="$1" 'index($0,k "=")==1 {print substr($0,length(k)+2); exit}' "$ENV_FILE"; fi; }
realm=$(env_get STATS_REALM_NAME); realm=${realm:-statsapp}
client=$(env_get STATS_DEV_CLI_CLIENT_ID); client=${client:-stats-dev-cli}
password=
case "$mode" in
  --password-stdin) IFS= read -r password || [ -n "$password" ] ;;
  auto)
    var=$(if [ -f "$ENV_FILE" ]; then awk -F= -v u="$user" '$1 ~ /^STATS_[A-Z_]+_USERNAME$/ && $2==u {sub(/_USERNAME$/, "_PASSWORD", $1); print $1; exit}' "$ENV_FILE"; fi)
    if [ -z "$var" ]; then var="STATS_$(printf %s "$user" | tr 'a-z-' 'A-Z_')_PASSWORD"; fi
    password=$(env_get "$var") ;;
esac
if [ -z "$password" ]; then
  if [ "$mode" = --password-stdin ]; then echo "Empty password." >&2; exit 1; fi
  # /dev/tty works even when stdout is captured: TOKEN=$(./docker/token.sh USER).
  if ! saved_stty=$(stty -g </dev/tty 2>/dev/null); then
    echo "No fixture password for '$user'. Use --password-stdin or run in a terminal." >&2; exit 1
  fi
  trap 'stty "$saved_stty" </dev/tty' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  printf 'Keycloak password for %s: ' "$user" >/dev/tty
  stty -echo </dev/tty
  IFS= read -r password </dev/tty || exit 1
  stty "$saved_stty" </dev/tty
  printf '\n' >/dev/tty
  trap - EXIT INT TERM
fi
# Pipe the encoded form to curl; do not expose passwords in process arguments.
encode() { printf %s "$1" | jq -sRr @uri; }
resp=$({
  printf 'grant_type=password&client_id=%s&username=%s&password=' "$(encode "$client")" "$(encode "$user")"
  printf %s "$(encode "$password")"
} | curl --silent --show-error --connect-timeout 10 --max-time 30 \
  -H 'Content-Type: application/x-www-form-urlencoded' --data-binary @- \
  "$KC/realms/$realm/protocol/openid-connect/token") || {
  echo "Keycloak is not reachable at $KC." >&2; exit 1
}
unset password
token=$(printf %s "$resp" | jq -er '.access_token // empty') || {
  printf %s "$resp" | jq -r '"Keycloak refused login: " + (.error_description // .error // "unexpected response")' >&2
  echo "If a fixture password is stale or belongs to a different installation, retry with --prompt." >&2
  exit 1
}
if [ "$show_claims" = false ]; then printf '%s\n' "$token"; exit 0; fi
payload=$(printf %s "$token" | cut -d. -f2 | tr '_-' '/+')
while [ $(( ${#payload} % 4 )) -ne 0 ]; do payload="$payload="; done
printf %s "$payload" | base64 -d | jq '{tenant, aud, roles: .realm_access.roles}'
