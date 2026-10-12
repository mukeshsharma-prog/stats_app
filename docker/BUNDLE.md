# Stats App Docker bundle

This archive contains every image and configuration file needed to run the local
Stats App stack without source code or registry access after download.

## Start

Prerequisite: Docker Engine or Docker Desktop with Compose v2.

```bash
tar -xzf stats-app-docker-bundle-<version>.tar.gz
cd stats-app-docker-bundle-<version>
./docker/start-stats-app.sh
```

The first start generates `.env` with random local credentials, loads the bundled
images, starts eight long-running containers (including Prometheus and PostgreSQL exporter),
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

Prometheus starts automatically. Open http://localhost:9090/targets; Input, Writer,
API and PostgreSQL should all be **UP**. The startup script waits for successful
scrapes before reporting ready. To repeat that check:

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

The v6 monitoring bundle includes the 6 October 2026 acknowledgement change. The older v3
archive returns an empty success body. The startup script always loads the images
from this archive, so existing mutable `:dev` tags cannot silently select older code.
To retain the existing credentials and data, copy the old bundle's `.env` into this
bundle before starting; both bundles use the same `stats-app-bundle` Compose project.
Re-import the new Postman collection to get its acknowledgement checks.

For the Postman desktop application, import both files in `postman/`. Replace the
environment's blank password values with the corresponding values from `.env`.

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

LTE/netstats support requires the v5 bundle. LTE checks include measured, 2G and
unknown-technology records. Primary LTE/netstats examples match the 9 October
live-export shapes using synthetic identifiers and addresses. Optional degraded
states, extra interfaces and numeric boundaries remain covered by source regression tests. LTE identifiers follow the current
string contract for local evaluation; D-12 still gates merge/shared release.
