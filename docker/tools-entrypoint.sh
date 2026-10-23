#!/usr/bin/env bash
# Entry point of the stats-tools image (docker/Dockerfile.tools).
#   shell   an interactive bash with docker/stats-helpers.sh loaded (the default)
#   demo    docker/demo.sh against the bundle's services
#   other   run as given, e.g. `bash -c '. /tools/docker/stats-helpers.sh && tok alpha-reader'`
set -eu

if [ ! -f "${STATS_ENV_FILE:-/bundle/.env}" ]; then
  echo "stats-tools: no .env mounted at ${STATS_ENV_FILE:-/bundle/.env}." >&2
  echo "  Start it with ./docker/shell.sh or ./docker/run-demo.sh from the bundle directory." >&2
  exit 1
fi

case "${1:-shell}" in
  shell)
    cd /bundle
    rc=$(mktemp)
    printf '%s\n' "PS1='stats-tools \$ '" ". /tools/docker/stats-helpers.sh" > "$rc"
    exec bash --rcfile "$rc" -i
    ;;
  demo)
    shift
    exec bash /tools/docker/demo.sh "$@"
    ;;
  *)
    exec "$@"
    ;;
esac
