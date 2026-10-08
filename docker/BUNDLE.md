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
images, starts all six long-running containers, and waits for every health check.

## Verify with Postman/Newman

```bash
./docker/run-postman.sh
```

The collection exercises both ingest doors, all four read API route shapes,
`lorastats`, `sysstats`, and `omsstats`, tenant/role authentication failures,
the GET-only rule, RabbitMQ topology and consumers, and Writer persistence metrics.
A successful ingest returns JSON `{"status":"accepted","traceId":"…"}` with a
matching `X-Trace-Id` header after broker confirmation. Database persistence remains
asynchronous; the collection polls the API until Writer commits.

This v4 bundle includes the 6 October 2026 acknowledgement change. The older v3
archive returns an empty success body. When upgrading a running v3 stack, load the
new images explicitly (`docker load -i stats-app-images.tar`) before restarting:
the startup script skips loading when the same `:dev` image tags already exist.
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
