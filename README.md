# Stats App

A Datahub Core service that accepts statistics from Heimdal, buffers them through
RabbitMQ, stores them in PostgreSQL, and exposes tenant-scoped reads through a REST API.

```text
Heimdal → stats-input → RabbitMQ → stats-writer → PostgreSQL → stats-api
                                                                ↑
                                                     Keycloak bearer token
```

All five V1 types are implemented: `lorastats`, `sysstats`, `omsstats`, `ltestats`
and `netstats`. The platform uses **Java 21**, **Spring Boot 3.5.16** and
**PostgreSQL 17**. Dependency versions are pinned in
[gradle/libs.versions.toml](gradle/libs.versions.toml).

## Start here

| Need | Read |
|---|---|
| How the system works, its invariants and why | [Architecture](docs/ARCHITECTURE.md) |
| Build, run, test or change the code | [Contributing](CONTRIBUTING.md) |
| API and ingest contracts, schemas, runbooks | [Documentation index](docs/README.md) |
| Run an offline Docker bundle | [Bundle guide](docker/BUNDLE.md) and [manual walkthrough](docker/MANUAL.md) |

## Current status

The Input, Writer and API support all five types. Publisher confirmations, manual
acknowledgments, bounded retries, per-type dead-letter queues, partition retention,
tenant filtering and operational metrics are implemented. LTE IMSI is masked on API
reads; LTE/network payloads never reach logs at any level. Keycloak users, roles and the
administrator-only Tenant attribute are managed in the Keycloak console.

### Known gaps before production

* **Inbound authentication.** `stats.input.auth.mode=oauth2` is implemented (Keycloak
  client-credentials token with the `stats.ingest` role), but Heimdal's mechanism has not
  been agreed with ZC, and the local stack still runs `none`: anything that can reach the
  Input port can write. `prod` refuses to start in that mode. The `/external/stats` door
  binds nothing to the record's tenant yet, and `mtls` is not implemented.
* **The Compose stack is a development stack.** Keycloak runs `start-dev` on its embedded
  H2 database, credentials are generated locally, and every service is a single instance.
* **No production deployment configuration.** There are no deployment manifests, no
  secrets-manager integration and no per-environment configuration yet; the platform owner
  provides those, along with log retention, encryption at rest and backup tooling.
* **External confirmations** are still open: the ingest contract, Keycloak names, the data-
  handling decision and the remaining ZC questions listed in
  [docs/ARCHITECTURE.md §11](docs/ARCHITECTURE.md#11-open-external-decisions).

## Run a distributed bundle

Extract the archive, open a shell in the extracted directory, and run:

```sh
./docker/start-stats-app.sh
```

The bundle includes AMD64 and ARM64 images and needs Docker with Compose and a Linux
shell; registry access is unnecessary after extraction. Windows users should use
Ubuntu under WSL 2. See [docker/BUNDLE.md](docker/BUNDLE.md) for the full setup.

On a fresh installation, startup generates a private `.env`. When reusing existing
data volumes, retain the matching `.env`; editing it alone does not reset stored
PostgreSQL or RabbitMQ passwords. Before replacing a legacy Keycloak container,
follow the migration instructions in the bundle guide. Deleting volumes deletes data.

The checkout and bundle use fixed container names and host ports. Run one installation
at a time. Keep deployed bundle directories and their `.env` files together.

## Develop from source

Use JDK 21, the included Gradle wrapper, and Docker with Compose. If this checkout has
no `.env`, create it from the example and fill every required secret before startup:

```sh
test -f .env || cp .env.example .env
```

For a complete source-built local stack:

```sh
docker compose --env-file .env -f docker/docker-compose.yml --profile apps up -d --build
```

For IDE/Gradle development, start infrastructure only and follow the environment
setup in [CONTRIBUTING.md](CONTRIBUTING.md):

```sh
docker compose --env-file .env -f docker/docker-compose.yml up -d
```

Never commit `.env`, private Postman environments, tokens or generated credentials.

| Module | Purpose |
|---|---|
| `stats-input` | Validate the envelope and confirm publication to RabbitMQ |
| `stats-writer` | Validate per-type payloads, persist, retry, dead-letter and maintain partitions |
| `stats-api` | Validate bearer tokens and perform tenant-filtered reads with the internal `stats_ro` database login |
| `stats-common` | Shared domain, schemas, topology and safe error contracts |
| `stats-testsupport` | Shared integration-test infrastructure |

Application modules depend on `stats-common`, not on each other. Runtime schemas,
fixtures, contracts and Flyway migrations are part of the implementation, not disposable
documentation.

## Local service addresses

| Service | Address |
|---|---|
| Keycloak admin console | http://localhost:8080/admin/ |
| Input | `POST http://localhost:8081/internal/stats` |
| Writer health | http://localhost:8082/actuator/health |
| Read API | `GET http://localhost:8083/v1/stats/{type}?from={epoch}&to={epoch}` |
| RabbitMQ management | http://localhost:15672 |
| PostgreSQL | `localhost:5432`, database `statsdb` |
| Prometheus targets | http://localhost:9090/targets |
| PostgreSQL metrics | http://localhost:9187/metrics |
| Grafana, when its optional profile is enabled | http://localhost:3000 |

Each application exposes `/actuator/health` and `/actuator/prometheus` on its own port.
For Keycloak users, select the `statsapp` realm, assign the realm role `stats.readOnly`,
and set a single Tenant value. In the Assign role dialog, filter by **realm roles**.
`stats_ro` is an internal PostgreSQL login, not a Keycloak user role.

```sh
./docker/token.sh your-user --prompt --claims
```

## Verification

Run the Java suite with Docker available:

```sh
./gradlew test
```

Schema/contract checks use the Python dependencies listed in
[docs/schemas/requirements.txt](docs/schemas/requirements.txt). With those installed:

```sh
python3 docs/schemas/validate-fixtures.py
python3 docs/contracts/check_schema_drift.py
python3 docs/contracts/test_schema_drift.py
```

Docker helper tests are standalone scripts:

```sh
for test_file in docker/tests/test-*.py; do python3 "$test_file" || exit; done
```

The [manual verification runbook](docs/runbooks/manual-verification.md) covers API
routes, partitions and queues. The [bundle guide](docker/BUNDLE.md) describes the
repeatable `zra-reader` checks; that verifier inserts synthetic records, so choose an
appropriate local test installation.

## Package an offline bundle

Choose a version label and run:

```sh
./docker/package-bundle.sh my-version
```

The archive and SHA-256 checksum are written to `dist/`. `docker/BUNDLE.md` is copied
to the archive's root `README.md`; the detailed guide is also included as `BUNDLE.md`.
A custom README can be supplied through `BUNDLE_README`:

```sh
BUNDLE_README=/absolute/path/to/custom-readme.md ./docker/package-bundle.sh my-version
```

Bundle images, deployed credentials and local recovery backups remain outside Git.
Generated API HTML can be rebuilt with `docs/contracts/build-html.sh` and is published
by CI as an artifact. The source OpenAPI YAML remains authoritative.
