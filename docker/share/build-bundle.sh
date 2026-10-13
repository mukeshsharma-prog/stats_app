#!/usr/bin/env bash
# Stats App — build the share bundle .
#
#   ./docker/share/build-bundle.sh                     # amd64 + arm64, infrastructure pulled on first run
#   WITH_INFRA=1 ./docker/share/build-bundle.sh        # also ship PostgreSQL/RabbitMQ/Keycloak: runs offline
#   PLATFORMS=linux/amd64 ./docker/share/build-bundle.sh
#
# Output: build/share/stats-app-share.tar.gz — one file to hand to someone. It unpacks
# to a directory holding the images, a compose file that never builds, a .env with
# FRESHLY GENERATED dev-only secrets, the configuration the stack mounts, and a
# README written for the person receiving it. They need Docker and nothing else.
#
# What it never contains: your .env. Every secret in the bundle is generated here,
# for this bundle, from .env.example — so a variable added to .env.example without a
# rule below fails this script instead of shipping blank.
#
# Needs Docker's containerd image store (the Docker Desktop default since 4.34), which
# is what lets one `docker save` carry both architectures.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"
WITH_INFRA="${WITH_INFRA:-0}"
TAG=share
NAME=stats-app-share
OUT="$REPO/build/share"
DIR="$OUT/$NAME"

MODULES=(stats-input stats-writer stats-api)
INFRA=(postgres:17-alpine rabbitmq:4-management-alpine quay.io/keycloak/keycloak:26.0)

say() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
die() { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }
rand() { openssl rand -hex 16; }

command -v openssl >/dev/null || die "openssl is required"
docker info --format '{{json .DriverStatus}}' | grep -q containerd \
  || die "Docker's containerd image store is off. Docker Desktop: Settings > General > 'Use containerd for pulling and storing images'."

say "1. Images for $PLATFORMS"
for m in "${MODULES[@]}"; do
  echo "-- $m"
  docker buildx build --platform "$PLATFORMS" -f "$REPO/docker/Dockerfile" \
    --build-arg MODULE="$m" -t "stats-app/$m:$TAG" --load "$REPO"
done
echo "-- stats-demo"
docker buildx build --platform "$PLATFORMS" -f "$REPO/docker/share/Dockerfile.demo" \
  -t "stats-app/stats-demo:$TAG" --load "$REPO"

IMAGES=("${MODULES[@]/#/stats-app/}"); IMAGES=("${IMAGES[@]/%/:$TAG}" "stats-app/stats-demo:$TAG")
if [ "$WITH_INFRA" = 1 ]; then
  say "1b. Infrastructure images, every platform (WITH_INFRA=1)"
  for img in "${INFRA[@]}"; do
    for p in ${PLATFORMS//,/ }; do docker pull -q --platform "$p" "$img"; done
    IMAGES+=("$img")
  done
fi

say "2. Bundle directory"
rm -rf "$DIR"; mkdir -p "$DIR/config"
cp "$REPO/docker/share/docker-compose.yml"                "$DIR/"
install -m 0755 "$REPO/docker/share/start.sh"            "$DIR/start.sh"
cp -R "$REPO/docker/postgres-init"                        "$DIR/config/postgres-init"
mkdir -p "$DIR/config/prometheus" "$DIR/config/grafana" "$DIR/config/postgres-exporter"
cp "$REPO"/ops/prometheus/prometheus.yml "$REPO"/ops/prometheus/*-alerts.yml "$DIR/config/prometheus/"
cp -R "$REPO/ops/grafana/provisioning" "$REPO/ops/grafana/dashboards" "$DIR/config/grafana/"
cp "$REPO/ops/postgres-exporter/queries.yml"              "$DIR/config/postgres-exporter/"

say "3. .env — generated secrets, dev-only, for this bundle alone"
RABBIT_PASS=$(rand)
while IFS= read -r line; do
  case "$line" in
    STATS_INPUT_AUTH_MODE=*)          echo "STATS_INPUT_AUTH_MODE=basic" ;;
    STATS_INPUT_BASIC_USERNAME=)      echo "STATS_INPUT_BASIC_USERNAME=heimdal" ;;
    STATS_API_CURSOR_SIGNING_KEY=)    echo "STATS_API_CURSOR_SIGNING_KEY=$(openssl rand -base64 48 | tr -d '\n')" ;;
    RABBITMQ_PASSWORD=|RABBITMQ_DEFAULT_PASS=) echo "${line}${RABBIT_PASS}" ;;
    # Deliberately blank: set by compose on stats-api / only during a rotation.
    KEYCLOAK_JWK_SET_URI=|STATS_INPUT_BASIC_PREVIOUS_PASSWORD=) echo "$line" ;;
    *_PASSWORD=|*_SECRET=)            echo "${line}$(rand)" ;;
    [A-Z]*=)                          die "no rule for the blank variable ${line%=} in .env.example — add one to build-bundle.sh" ;;
    *)                                echo "$line" ;;
  esac
done < "$REPO/.env.example" > "$DIR/.env"
cat >> "$DIR/.env" <<'EOF'


# === Share bundle — host ports (DL-164) =====================================
# Read by compose for ${PORT_*} interpolation in docker-compose.yml. Every port binds
# to 127.0.0.1 only. ./start.sh checks each one first: a port already taken on this
# machine is moved to the next free one, with a warning, and rewritten here.
# PostgreSQL is 5433, not 5432, because 5432 is usually a local PostgreSQL already.
PORT_KEYCLOAK=8080
PORT_INPUT=8081
PORT_WRITER=8082
PORT_API=8083
PORT_POSTGRES=5433
PORT_RABBITMQ_UI=15672
PORT_PROMETHEUS=9090
PORT_GRAFANA=3000
EOF

say "3b. Keycloak realm, rendered with this bundle's values"
# Keycloak 26.0's --import-realm resolves every ${env.NAME:fallback} in the realm to its
# FALLBACK, whatever the container's environment holds (measured 29 Sep 2026, DL-164):
# a generated password in .env would never reach Keycloak and every login would fail.
# So the bundle's copy is rendered here, from the bundle's own .env, and holds no
# placeholder at all. The realm file in docs/setup/ is unchanged.
perl - "$DIR/.env" "$REPO/docs/setup/keycloak-realm-statsapp.json" > "$DIR/config/keycloak-realm-statsapp.json" <<'PERL'
open my $fh, '<', $ARGV[0] or die $!;
my %e;
while (<$fh>) { next unless /^([A-Z0-9_]+)=(.*)$/; my ($k, $v) = ($1, $2); $v =~ s/^'(.*)'$/$1/; $e{$k} = $v }
open my $in, '<', $ARGV[1] or die $!;
local $/; my $j = <$in>;
$j =~ s/\$\{env\.([A-Z0-9_]+):([^}]*)\}/(defined $e{$1} && length $e{$1}) ? $e{$1} : $2/ge;
print $j;
PERL
if grep -q '\${env\.' "$DIR/config/keycloak-realm-statsapp.json"; then die "unresolved placeholder left in the rendered realm"; fi
jq -e . "$DIR/config/keycloak-realm-statsapp.json" >/dev/null || die "rendered realm is not valid JSON"

say "4. README, with this bundle's values"
envv() { grep -E "^$1=" "$DIR/.env" | head -1 | cut -d= -f2-; }
COMMIT="$(git -C "$REPO" rev-parse --short HEAD)"
git -C "$REPO" diff --quiet HEAD -- . ':!build' 2>/dev/null || COMMIT="$COMMIT + uncommitted changes"
sed -e "s|@@BUILT@@|$(date '+%d %B %Y')|" \
    -e "s|@@COMMIT@@|$COMMIT|" \
    -e "s|@@PLATFORMS@@|$PLATFORMS|" \
    -e "s|@@INFRA@@|$([ "$WITH_INFRA" = 1 ] && echo 'included — no internet needed' || echo 'pulled from Docker Hub / quay.io on first start')|" \
    -e "s|@@BASIC_USER@@|$(envv STATS_INPUT_BASIC_USERNAME)|g" \
    -e "s|@@BASIC_PASS@@|$(envv STATS_INPUT_BASIC_PASSWORD)|g" \
    -e "s|@@ALPHA_PASS@@|$(envv STATS_ALPHA_READER_PASSWORD)|g" \
    -e "s|@@BETA_PASS@@|$(envv STATS_BETA_READER_PASSWORD)|g" \
    -e "s|@@KC_ADMIN_PASS@@|$(envv KC_BOOTSTRAP_ADMIN_PASSWORD)|g" \
    -e "s|@@RABBIT_PASS@@|$RABBIT_PASS|g" \
    "$REPO/docker/share/README.md" > "$DIR/README.md"

say "5. images.tar (${#IMAGES[@]} images)"
docker save -o "$DIR/images.tar" "${IMAGES[@]}"

say "6. Archive"
tar -C "$OUT" -czf "$OUT/$NAME.tar.gz" "$NAME"
ls -lh "$OUT/$NAME.tar.gz"
echo
echo "Share: $OUT/$NAME.tar.gz   (instructions inside: $NAME/README.md)"
