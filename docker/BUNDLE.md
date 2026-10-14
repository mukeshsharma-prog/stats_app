# Stats App Docker bundle

This archive contains every image and configuration file needed to run the local
Stats App stack without source code or registry access after download.

| File | For |
|---|---|
| `README.md` (this file) | Start, verify with the automated suite, stop |
| **`MANUAL.md`** | **The walkthrough by hand.** Every command and what it should print: log in, send a record, read it back, check that another tenant can't see it, the IMSI mask, rejections, the dead-letter queue, duplicates, logs |
| `docker/stats-helpers.sh` · `docker/shell.sh` · `docker/token.sh` · `docker/run-demo.sh` | The helpers `MANUAL.md` uses, a shell with them loaded, a token printer, the ten-step scripted demo |

## Start

Prerequisite: Docker Engine or Docker Desktop with Compose v2, on an Intel/AMD
(`amd64`) or ARM (`arm64`, e.g. Apple Silicon) machine. Nothing else — no Java,
Gradle, Postman or registry access. (`MANUAL.md` can use `curl` and `jq` on your
machine if you have them; `./docker/shell.sh` gives you both in a container if not.) Ports 8080–8083, 5432, 5672, 15672, 9090, 9187
(and 3000 for Grafana) must be free on the machine.

Check the download first; the `.sha256` file is delivered beside the archive:

```bash
sha256sum -c stats-app-docker-bundle-<version>.tar.gz.sha256   # macOS: shasum -a 256 -c …
```

```bash
tar -xzf stats-app-docker-bundle-<version>.tar.gz
cd stats-app-docker-bundle-<version>
./docker/start-stats-app.sh
```

The first start generates `.env` with random local credentials, loads the bundled
images for this machine's architecture (`stats-app-images-amd64.tar` or
`stats-app-images-arm64.tar`; the other one is never read), starts eight long-running containers (including Prometheus and PostgreSQL exporter),
waits for their health checks, and verifies all four metrics targets are UP.

When upgrading an existing installation, copy its `.env` into the extracted bundle
**before running the startup script**. PostgreSQL keeps its database passwords in
the persistent volume; setting new environment passwords does not change them.
The script refuses to generate new credentials when application data volumes
already exist. For a healthy running database, it also checks both database roles
using the supplied `.env` before changing application containers.

If startup reports `password authentication failed`, restore the matching database
credentials in `.env` and start again. If the broker or Keycloak has already been
initialized with new credentials, preserve those working settings and restore only
the PostgreSQL settings. Keep a private backup of `.env` with the data backups.

## Monitoring

Prometheus starts automatically and does **not** wait for the applications to be
healthy (DL-167): if one of them fails, Prometheus is already running and
http://localhost:9090/targets shows that target as **DOWN**. Normally Input, Writer,
API and PostgreSQL should all be **UP**.

`start-stats-app.sh` exit status:

| Status | Meaning |
|---|---|
| `0` | Ready — every application healthy and every Prometheus target up |
| `1` | Not started — a container failed or timed out (its last log lines are printed) |
| `3` | Applications ready, **monitoring degraded** — the stack works, but at least one target is not up; the failing samples are printed |

To repeat the monitoring check:

```bash
./docker/check-monitoring.sh
```

Metrics URLs:

- http://localhost:8081/actuator/prometheus
- http://localhost:8082/actuator/prometheus
- http://localhost:8083/actuator/prometheus
- http://localhost:9187/metrics

The local API permits its Prometheus endpoint without a token; stats reads still
require JWT authentication, the read role and tenant. Grafana is optional and its
image, provisioning files and dashboards are included for offline startup:

```bash
docker compose -p stats-app-bundle --env-file .env -f docker/docker-compose.yml --profile apps --profile observability up -d --no-build
```

Open http://localhost:3000 for Grafana. Use the same Compose project for every
service so its DNS names resolve on the shared network.

## Verify with Postman/Newman

```bash
./docker/run-postman.sh
```

The collection exercises both ingest doors, all four read API route shapes,
all five types (`lorastats`, `sysstats`, `omsstats`, `ltestats`, `netstats`), tenant/role authentication failures,
the GET-only rule, RabbitMQ topology and consumers, and Writer persistence metrics.
A successful ingest returns JSON `{"status":"accepted","traceId":"…"}` with a
matching `X-Trace-Id` header after broker confirmation. Database persistence remains
asynchronous; the collection polls the API until Writer commits.

The startup script always loads the images from this archive, so existing mutable
`:dev` tags from an older bundle cannot silently select older code. To retain the
existing credentials and data, copy the old bundle's `.env` into this bundle before
starting; every bundle uses the same `stats-app-bundle` Compose project. Re-import the
Postman collection from this bundle — older copies lack the newer checks.

For the Postman desktop application, import both files in `postman/`. Replace the
environment's blank password values with the corresponding values from `.env`.
`./docker/token.sh alpha-reader` prints a ready-made bearer token if you only want one
request.

Run the suite **first**, on a freshly started stack. The walkthrough's step 10 and the
scripted demo dead-letter a record on purpose, and the suite's `DLQs stayed empty` check
then fails, correctly. `MANUAL.md` → *Start again* resets the stack.

## Walk through it by hand

Open **`MANUAL.md`**. It has fourteen steps, each with the command to paste and the
output to expect. **Run every command from this directory.** The passwords are in this
directory's `.env`, which was generated on first start. Another copy of the Stats App,
such as an older bundle or a developer checkout, has different passwords, and its
Keycloak answers "Invalid user credentials" for these.

```bash
. docker/stats-helpers.sh        # bash/zsh helpers: tok, claims, env_get, pg (needs curl, jq)
./docker/shell.sh                # or: the same helpers in a container, nothing installed but Docker
./docker/run-demo.sh             # the ten-step scripted demo; step 10 stops RabbitMQ briefly
```

## Stop

```bash
./docker/stop-stats-app.sh
```

Data is retained in Docker volumes. To deliberately delete all local data, run
`docker compose -p stats-app-bundle -f docker/docker-compose.yml --profile apps down -v`.

## Important scope

The bundle uses `STATS_INPUT_AUTH_MODE=none` for isolated local evaluation. Anyone
who can reach port 8081 can submit records. Do not expose this configuration on a
shared or production network.

The runtime intentionally consists of separate containers. `stats-common` is a
library embedded in the three application images; it is not a fourth application.

All five stat types are built end to end. LTE checks include measured, 2G and
unknown-technology records. Primary LTE/netstats examples match the 9 October
live-export shapes using synthetic identifiers and addresses. Optional degraded
states, extra interfaces and numeric boundaries remain covered by source regression
tests. **`imsi` is stored as received and masked on every read API response** (carrier
prefix and last four digits visible, the rest `*`, length kept — DL-167); `lac`/`cid`
are returned as reported. No LTE/netstats payload is written to any log at any level.

Input refuses, with a 400, a record PostgreSQL could not store — an over-long `uid`,
a blank `tenant`, an out-of-range `version`, a NUL character — instead of accepting it
and dead-lettering it later (DL-168).
