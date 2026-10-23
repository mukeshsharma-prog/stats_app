# Contributing

How to build, run, test and change the Stats App. Read
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) first: it holds the invariants every change
must keep, and the reasons behind them.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 | Not 17 |
| Gradle | the wrapper, `./gradlew` | Do not install Gradle globally |
| Docker with Compose | recent | Local PostgreSQL, RabbitMQ and Keycloak; Testcontainers |
| Python | 3 (CI uses 3.13) | Schema, contract and Docker-helper checks; dependencies in `docs/schemas/requirements.txt` |

Every dependency version lives in [gradle/libs.versions.toml](gradle/libs.versions.toml).
A module build file never declares a version of its own.

## Local setup

Create `.env` from the example and fill every secret before startup:

```sh
test -f .env || cp .env.example .env
```

Infrastructure only, for running the modules from Gradle or an IDE:

```sh
docker compose --env-file .env -f docker/docker-compose.yml up -d
```

The whole stack, built from this checkout:

```sh
docker compose --env-file .env -f docker/docker-compose.yml --profile apps up -d --build
```

Add `--profile observability` for Prometheus, `postgres_exporter` and Grafana. Rebuild the
images after pulling or merging: running containers silently lag the checkout.

Running a module from Gradle needs the same environment. Boot does not read `.env`:

```sh
set -a; . ./.env; set +a
./gradlew :stats-writer:bootRun
```

Start the Writer first, because it applies the migrations, then in separate terminals:

```sh
./gradlew :stats-input:bootRun
./gradlew :stats-api:bootRun
```

Two local traps:

* Use `docker exec stats-postgres psql -U stats -d statsdb`. A PostgreSQL installed on the
  host can own `127.0.0.1:5432` ahead of Docker's port mapping, and `psql -h localhost`
  then fails with `role "stats" does not exist`.
* Keep `.env` safe to `source`: quote any value containing `(` or spaces, as `.env.example`
  does. An unquoted one aborts `source` halfway and leaves later variables unset.

`docker/demo.sh` runs the end-to-end demo against the `apps` profile: both validation gates,
duplicate suppression, late arrival, the security boundaries and a broker outage. Run it
after any change to the consume path.

The local stack runs Input unauthenticated (`STATS_INPUT_AUTH_MODE=none`). To exercise
ingest authentication, set it to `oauth2` in `.env` with `STATS_INPUT_OAUTH2_ISSUER_URI` and
`STATS_INPUT_OAUTH2_AUDIENCE` as in `.env.example`, and send a client-credentials token from
the `stats-input` client.

For Keycloak users and tokens see
[docs/setup/keycloak-realm-statsapp.md](docs/setup/keycloak-realm-statsapp.md):

```sh
./docker/token.sh your-user --prompt --claims
```

## Build and test

Everything (Docker must be running), one module, or one suite:

```sh
./gradlew build
./gradlew :stats-writer:test
./gradlew :stats-api:test --tests "*TenantIsolation*"
```

Integration tests run against real PostgreSQL 17 and RabbitMQ through Testcontainers. Do not
replace them with an in-memory database or a mocked broker: partition pruning, constraint
semantics, publisher confirms and returned messages are exactly what such a substitute
cannot reproduce.

The non-Java gates, all run by CI:

```sh
python3 docs/schemas/validate-fixtures.py
python3 docs/contracts/check_schema_drift.py
python3 docs/contracts/test_schema_drift.py
python3 ops/grafana/check-dashboards.py
for t in docker/tests/test-*.py; do python3 "$t" || exit; done
```

`ops/prometheus/README.md` has the `promtool` commands for the alert rules.

Tests that must exist for every behaviour they protect:

* every validation rule, positive and negative;
* tenant isolation on fixtures where `tenant` differs from `maintenant`;
* no 2xx before a publisher confirm, and 503 when the broker is down or a message is unroutable;
* the retry and dead-letter path on a write failure, per type, with the hand-off confirmed
  before the original is acknowledged;
* a poison message in one type does not delay another;
* a duplicate redelivery is a metered no-op;
* the retention boundary on either side, and a late-arriving record;
* every non-GET method rejected on every API route;
* keyset pagination stable under concurrent writes.

## Code conventions

| Thing | Convention | Example |
|---|---|---|
| Table | stat type, lowercase | `lorastats` |
| Main queue | `app_statsstore.<type>` | `app_statsstore.lorastats` |
| Retry queues | `app_statsstore.<type>.retry.<tier>` | `app_statsstore.lorastats.retry.1` |
| Dead-letter queue | `app_statsstore.<type>.dlq` | `app_statsstore.lorastats.dlq` |
| Configuration property | `stats.<area>.<name>` | `stats.retention.default-days` |
| Endpoint | `/v{n}/stats/{type}` | `/v1/stats/lorastats` |

* Constructor injection only. Controllers validate, delegate and map; nothing more.
* A new stat type is additive: a schema, a consumer descriptor, a batch writer, a reader, a
  migration and fixtures. `StatTypeFanOutTest` fails the build when main code special-cases
  a type outside its documented list.
* Queue and exchange arguments come from `AmqpTopology`, never hand-assembled.
* Anything that might change is a property, and a property that decides safety (retention,
  auth mode, issuer, audience) has **no default in code**: an unset value stops the context.
  Refuse a blank value as well as an absent one, and test the blank case through the binder.
* A property nothing reads is deleted, not left to look configured.
* Every schema change is a new versioned Flyway migration. **Never edit an applied
  migration**: Flyway checksums it, and the comments are part of the checksum.
* A new stat-type table carries `PRIMARY KEY (ts_device, uid)`, the `clsbox` canonical-case
  `CHECK`, `passthrough jsonb` and `data_extra jsonb`, and is `PARTITION BY RANGE (ts_device)`.
  Counters are `bigint`, range-checked through `WireNumbers` before conversion.
* Comments explain why the code is the way it is. Do not cite ticket, decision-log or
  meeting identifiers: the reason belongs in the comment, and the history belongs in the
  commit message and the pull request.

### Errors

* Never swallow an exception: handle it, rethrow it, or dead-letter the record.
* Validation failures are RFC 9457 Problem Details naming every failing field and a reason
  code. Never echo the offending value.
* Broker unavailable or confirm timeout is 503; Heimdal owns the retry.
* Unauthenticated is 401, wrong role or missing tenant claim is 403, a `uid` in another
  tenant is 404 exactly like a `uid` that does not exist.

### Logging

The control is the **level**, not the code path:

* Full payloads only at `DEBUG`, which is off in production. Never at `INFO` or above, and
  never a token or secret at any level.
* `ltestats` and `netstats` payloads (IMSI, cell, network addresses) are never logged at
  any level, `DEBUG` included. `LteNetstatsPayloadsAreNeverLoggedIntegrationTest` in each
  module proves it with Spring at `TRACE`; the framework loggers that would leak are pinned
  in each `application.properties`. Do not unpin them in production.
* A failure is logged through `SafeFailure.describe(failure)`: the exception types and
  `SQLState`, never a message or stack trace, because a PostgreSQL `DETAIL:` line quotes the
  offending row. `WriterLogsCarryNoThrowableTest` fails the build on a throwable in a Writer
  log call.
* The MDC scope owns `traceId`, `uid`, `statType`, `tenant`, `clsbox` and `errorCode`. A log
  statement adds the event, never one of those keys again: the ECS encoder drops a line with
  a duplicate key, silently after the fourth time. `LogsDoNotShadowMdcKeysTest` enforces it.

Which fields are sensitive, and where each one comes to rest, is catalogued in
[docs/decisions/DATA-SENSITIVITY-INVENTORY.md](docs/decisions/DATA-SENSITIVITY-INVENTORY.md).

## Secrets

Secrets come from the environment and never from a committed file. Never commit `.env`,
`*.private.postman_environment.json`, tokens, generated credentials or bundle archives;
`.gitignore` covers the usual names. The development defaults in `.env.example` are
deliberately fake and must not be reused anywhere shared.

## Pull requests

* Branch from `main`; one concern per pull request.
* Explain *why* in the description, and name the external decision or question it depends on.
* Tests added or updated for the behaviour that changed.
* Queries, controllers or security changed: the tenant-isolation suite was run and reviewed by
  someone who did not write the change.
* Schema changed: a new migration, fixtures, `validate-fixtures.py`'s expected count, and the
  OpenAPI contract if the API shape moved (`check_schema_drift.py` will say so).
* No hard-coded configuration, no new dependency without a reason.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Messages published but the queue stays empty | Routing key does not match a binding; Input answers 503 and `records_unroutable` moves |
| Queue grows and never drains | No consumer, or PostgreSQL unreachable — `docs/runbooks/queue-troubleshooting.md` |
| `RESOURCE_LOCKED` on startup | Two modules declared the same queue with different arguments |
| 400 `server_clock_ahead` on a fresh fixture | The fixture's `ts.server` is more than an hour ahead of Input's clock, or the host clock is behind |
| 400 `malformed_json` | The body is parsed as bytes, strictly: invalid UTF-8, a duplicate member or trailing content |
| `records_handoff_unconfirmed` moving, one type slow | The retry or DLQ publish is not confirmed (deleted queue, broker resource alarm) |
| API 401 with a valid-looking token | Issuer or audience mismatch, or the token expired |
| API returns nothing for data that exists | The token's `tenant` claim; check `tenant`, never `maintenant` |
| API 503 on a wide read | The 10 s `statement_timeout` cancelled it |
| Timestamps in 1970 | Seconds read as milliseconds |
| Records expire "early" | Retention keys on `ts.device`; a late gateway's records arrive old |
| Retention never runs on a laptop | The daily cron does not fire while the machine sleeps; `ops/drills/advisory-lock-contention.sh` fires it on demand |
