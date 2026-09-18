#!/usr/bin/env bash
# Render BOTH OpenAPI contracts into ONE reviewable HTML page (DL-145).
#
#   docs/contracts/openapi-input.yaml   Heimdal inbound  — POST /stats
#   docs/contracts/openapi.yaml         read API         — GET /v1/stats/{type}…
#           │
#           ▼  redocly join        (dedupes the shared $ref'd schemas; refuses
#           │                       to guess when two components genuinely differ)
#           ▼  merge-info.py       (join keeps only the FIRST file's `info`, which
#           │                       would drop the read API's principles — this puts
#           │                       both intros back under one umbrella title)
#           ▼  redocly build-docs  (prerendered static HTML; the Redoc viewer script
#                                   itself loads from cdn.redocly.com — the text is
#                                   readable without it, the sidebar is not)
#           │
#   stats-api-v1-draft.html   (repository root — where DL-117 always put it)
#
# Why one HTML and two YAMLs: ZC is the audience for both confirmations ("create an
# OpenAPI definition and we will confirm/decline it"), so they get one document —
# but the two contracts stay separate sources because they have different callers,
# different authentication and no shared version segment (the SCOPE notes in each).
#
# The rendered HTML is NOT committed — DL-117 decided that: a checked-in bundle is
# stale the moment its YAML changes and is 350 KB of noise in every diff, so
# .gitignore lists it. CI runs this script on every push and uploads the result as
# the `stats-api-v1-draft` workflow artifact, which is where a reviewer without Node
# downloads it from. Pinned to the same Redocly release as the lint gate: an unpinned
# renderer changes the page on a release we did not make. The output is
# byte-deterministic for a given input (verified 18 Sep 2026).
#
# Usage, from anywhere:   docs/contracts/build-html.sh
# Requires: node (npx), python3 with PyYAML — both already required by CI.

set -euo pipefail

REDOCLY_VERSION="2.51.2"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${HERE}/../.." && pwd)"
OUT="${REPO_ROOT}/stats-api-v1-draft.html"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

cd "${REPO_ROOT}"

# Order matters: the ingest contract leads, because the page's first reader is the
# team configuring Heimdal. Both `info` blocks survive via merge-info.py regardless.
npx --yes "@redocly/cli@${REDOCLY_VERSION}" join \
  docs/contracts/openapi-input.yaml \
  docs/contracts/openapi.yaml \
  --without-x-tag-groups \
  -o "${WORK}/joined.yaml" >/dev/null

python3 "${HERE}/merge-info.py" \
  "${WORK}/joined.yaml" \
  docs/contracts/openapi-input.yaml \
  docs/contracts/openapi.yaml \
  -o "${WORK}/merged.yaml"

npx --yes "@redocly/cli@${REDOCLY_VERSION}" build-docs "${WORK}/merged.yaml" \
  --title "Stats App — API contracts v1 (draft)" \
  --disableGoogleFont \
  --theme.openapi.expandResponses=200,400,503 \
  --theme.openapi.pathInMiddlePanel=true \
  -o "${OUT}" >/dev/null

echo "rendered ${OUT#${REPO_ROOT}/}"
