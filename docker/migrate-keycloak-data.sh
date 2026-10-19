#!/usr/bin/env sh
# Preserve a legacy dev-file Keycloak database before attaching keycloak-data.
# Run while no other process is administering Keycloak. PostgreSQL is untouched.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ "${ROOT##*/}" = docker ]; then ROOT=$(dirname "$ROOT"); fi
container=${1:-stats-keycloak}
project=$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$container")
[ -n "$project" ] || { echo "Container has no Compose project label." >&2; exit 1; }
volume="${project}_keycloak-data"
mounted=$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/opt/keycloak/data"}}{{.Name}}{{end}}{{end}}' "$container")
[ -z "$mounted" ] || { echo "Keycloak already uses persistent storage: $mounted"; exit 0; }
if docker volume inspect "$volume" >/dev/null 2>&1; then
  echo "$volume already exists. Refusing to overwrite it; inspect it before migrating." >&2; exit 1
fi
image=$(docker inspect -f '{{.Image}}' "$container")
was_running=$(docker inspect -f '{{.State.Running}}' "$container")
umask 077
backup="$ROOT/build/keycloak-backups/$container-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$backup"
helper=
succeeded=false
cleanup() {
  [ -z "$helper" ] || docker rm -f "$helper" >/dev/null 2>&1 || true
  if [ "$succeeded" = false ] && [ "$was_running" = true ]; then docker start "$container" >/dev/null || true; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# H2 must be copied while stopped. Keep this backup even after a successful migration.
docker stop "$container" >/dev/null
docker cp "$container:/opt/keycloak/data/h2" "$backup/h2"
docker volume create --label "com.docker.compose.project=$project" \
  --label com.docker.compose.volume=keycloak-data "$volume" >/dev/null
helper=$(docker create --user 0 --entrypoint /bin/bash \
  --mount "type=volume,source=$volume,target=/persist" "$image" \
  -ec 'chown -R 1000:0 /persist; chmod -R u+rwX /persist')
docker cp "$backup/h2" "$helper:/persist/h2"
docker start -a "$helper" >/dev/null
succeeded=true
printf 'Preserved Keycloak data in %s. Private backup: %s\n' "$volume" "$backup"
echo "The old container is stopped. Start the updated Compose stack to attach the volume."
