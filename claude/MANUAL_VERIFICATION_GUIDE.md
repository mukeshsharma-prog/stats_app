# Manual verification: samples, APIs, queues and partitions

Use the running local `stats-app-bundle` deployment. The POST checks below insert
synthetic test records into this deployment; use unique UIDs. Existing sample exports
are retained separately and are not automatically imported by starting Docker.

## 1. Check the running containers and connection addresses

```bash
cd '/home/mukesh/Downloads/Work/Mukesh/Datahub Core StataApp/stats_app'
docker ps --filter label=com.docker.compose.project=stats-app-bundle
docker port stats-postgres
```

Expect eight healthy services: PostgreSQL, RabbitMQ, Keycloak, Input, Writer, API,
PostgreSQL exporter and Prometheus. PostgreSQL publishes host port **5432** to
container port **5432**. The application stack does not configure port 5433.

| Component | Connection |
| --- | --- |
| Input | http://localhost:8081 |
| Writer management endpoints | http://localhost:8082 |
| Read API | http://localhost:8083/v1/stats |
| Keycloak | http://localhost:8080 |
| RabbitMQ management | http://localhost:15672 |
| Prometheus targets | http://localhost:9090/targets |
| PostgreSQL exporter | http://localhost:9187/metrics |
| PostgreSQL | PostgreSQL protocol, localhost:5432, database statsdb |

PostgreSQL does not serve HTTP. Use DBeaver, pgAdmin, DataGrip or psql, rather than
opening `http://localhost:5432` in a browser. From another application container,
the database hostname is `postgres`, not `localhost`.

The running deployment's credentials are in
`dist/stats-app-docker-bundle-v6-prometheus-autostart/.env` after the 9 October
startup repair. Its database settings were recovered from the previous deployment,
while its working newly initialized broker/Keycloak credentials were preserved.
The repository-root `.env` differs from this deployment. Use the credentials from
the environment that actually initialized the persistent database/broker/realm.
For a different installation, use that installation's own `.env`.

## 2. Check application readiness and monitoring

Open each readiness URL, expecting HTTP 200 and `status: UP`:

- http://localhost:8081/actuator/health/readiness
- http://localhost:8082/actuator/health/readiness
- http://localhost:8083/actuator/health/readiness

Open each metrics URL, expecting HTTP 200 and Prometheus text:

- http://localhost:8081/actuator/prometheus
- http://localhost:8082/actuator/prometheus
- http://localhost:8083/actuator/prometheus
- http://localhost:9187/metrics

At http://localhost:9090/targets, expect four UP jobs: `stats-input`, `stats-writer`,
`stats-api`, `postgres`. In Prometheus's query page, `up` should be 1 for all four,
and `pg_up` should be 1. Equivalent terminal check: `./docker/check-monitoring.sh`.

## 3. Configure Postman and authenticate

1. Import `postman/stats-app.postman_collection.json` and
   `postman/local.postman_environment.json`.
2. Select the **Stats App — Local** environment.
3. Fill these environment values from the active deployment's `.env`:

| Postman value | Environment key |
| --- | --- |
| alphaReaderPassword | STATS_ALPHA_READER_PASSWORD |
| noRolePassword | STATS_NO_ROLE_PASSWORD |
| noTenantPassword | STATS_NO_TENANT_PASSWORD |
| rabbitPassword | RABBITMQ_PASSWORD |

4. Check usernames, client ID and `tenantAlpha` against the corresponding active
   environment values; shipped defaults are `alpha-reader`, `no-role`, `no-tenant`,
   `stats-dev-cli` and `tenant-alpha`.
5. For a new test run, clear the Postman environment's `runId` value. The collection
   creates new UID variables on its next request.
6. Send the three readiness requests, then the three authentication requests in
   collection order. Expect 200 and an access token. The scripts save `alphaToken`,
   `noRoleToken` and `noTenantToken` for subsequent requests.
7. If a token expires during manual testing, resend its authentication request.

## 4. Check LTE/netstats sample variants

Send the collection's internal LTE, LTE 2G, LTE unknown-technology and netstats
POST requests. Inspect each resolved JSON body before sending. Expect 200, or
configured 202, with `status: accepted` and a `traceId` matching the `X-Trace-Id`
response header. Acknowledgement confirms broker acceptance; persistence follows
asynchronously.

Then send the corresponding GET-by-UID requests. A brief initial 404 can occur
before Writer commits; retry. Expect 200 and compare the response `data` to the
submitted `data`, including spelling, omitted members and JSON value types.

| Supplied sample shape | Expected wire representation |
| --- | --- |
| 998 LTE 4G measurements | rssi/rsrp/rsrq, access_Technology, plmn, imsi, operator, lac, cid; all strings |
| One LTE 2G measurement | Same measured shape without rsrp/rsrq |
| One LTE error | data contains only etxt, as a string |
| 1,000 netstats records | eth0, wwan and integer timespan |
| eth0 | gw/tx/rx/ip/dns/mask; DNS is a string array, other observed values are strings |
| wwan | eth0 fields plus string driver and mtu |

For the etxt-only shape, duplicate the LTE POST request, use a new UID and replace
`data` with `{"etxt":"manual test: modem unavailable"}`. GET that new UID and
verify only etxt is returned in `data`.

The primary live-shape examples are in
`docs/schemas/fixtures/valid/ltestats-live-2026-10-09.json` and
`docs/schemas/fixtures/valid/netstats-live-2026-10-09.json`.
For additional optional variants use `ltestats-production-2g-no-rsrp.json`,
`ltestats-production-unknown-technology.json`, `netstats-production-wwan-down.json`,
`netstats-extra-interface.json` and `netstats-boundary.json` in the same directory.
When copying a fixture into a new request, remove schema metadata such as `$comment`,
assign a unique UID and the authenticated test tenant, and set all timestamp fields
to current Unix seconds for a new-record test. Keep the payload's original JSON types.

For the actual attached exports, send individual elements of the outer `data`
array, not the `{replyText, data, replyCode}` export wrapper. The record's own
`data` member is its statistics payload. Remove datastore `_id`, use test UIDs and
the test tenant. Preserve timestamps for historical-routing checks while they are
within retention. Expired records are acknowledged but intentionally not stored.
Checking a few records manually establishes representative coverage; it does not
replace the separate completed 2,000-record replay audit.

The optional netstats fields `fail_count`, `last_forced_reset` and `last_powercycle`
are absent from the supplied exports. They should stay absent on readback.

## 5. Check every API route

First send the LoRa, sysstats and OMS POST requests too, so all five types have a
known test record. For each type (`lorastats`, `sysstats`, `omsstats`, `ltestats`,
`netstats`) check all four routes with Bearer token `{{alphaToken}}`:

| GET route after http://localhost:8083 | Expected result |
| --- | --- |
| /v1/stats/TYPE?from=FROM&to=TO&limit=100 | 200, items contains the test UID |
| /v1/stats/TYPE/UID | 200, the submitted record |
| /v1/stats/TYPE/CLSBOX/FROM?limit=100 | 200, items contains the test UID |
| /v1/stats/TYPE/CLSBOX/FROM/TO?limit=100 | 200, items contains the test UID |

Replace placeholders with actual values. Use Unix **seconds**, with FROM before
the test's `ts.device` and TO after it. Collection timestamps/windows refresh on
each request; after a long manual pause use an explicit window around the original
POST timestamp. Follow `nextCursor` until null when a query spans multiple pages.

Send the external-door requests too. For a duplicate test, reuse the exact original
record including UID and `ts.device`. Changing `ts.device` changes the database key.
The collection's external LTE/netstats duplicate requests handle this for you.
Check that Writer's `records_duplicate_suppressed_total` rises and SQL row count
does not increase.

Check errors using copies of the requests:

| Change | Expected status |
| --- | ---: |
| Remove Bearer token | 401 |
| Use noRoleToken | 403 |
| Use noTenantToken | 403 |
| Request an absent UID with alphaToken | 404 |
| Use an unknown stat type with alphaToken | 400 |
| Set from equal to to with alphaToken | 400 |
| POST to a read API route | 405 |

To verify tenant separation, authenticate `beta-reader` using its active environment
password and the same dev client, then request an alpha-owned UID with that token.
Expect 404. Tenant scope comes from the token.

## 6. Check RabbitMQ queues and bindings

1. Open http://localhost:15672 and log in using `RABBITMQ_USER` /
   `RABBITMQ_PASSWORD` from the active environment.
2. Open **Queues and Streams** and filter by `app_statsstore.`.
3. Expect 25 queues. For each of the five types there should be:

```text
app_statsstore.TYPE
app_statsstore.TYPE.retry.1
app_statsstore.TYPE.retry.2
app_statsstore.TYPE.retry.3
app_statsstore.TYPE.dlq
```

4. Open each queue: type quorum, durable, auto-delete false. Each main queue should
   have an active consumer (one in this local deployment). After test processing,
   Ready and Unacked should settle to zero. Retry/DLQ queues should be empty.
5. Under **Exchanges**, inspect durable topic exchanges `app_stats_input` and
   `app_stats_dlx`.
6. For each TYPE, verify input exchange binding TYPE → main; DLX TYPE → main;
   DLX TYPE.dlq → DLQ.
7. Retry queue arguments should have TTL 30000 / 300000 / 1800000 milliseconds,
   dead-letter exchange `app_stats_dlx`, dead-letter routing key TYPE, and no
   consumers. Retry publishing uses the default exchange's queue-name binding.
8. DLQs should have no TTL, no dead-letter forwarding and no consumer.

`InputAmqpConfiguration` declares the input exchange. Writer declares and consumes
the queues; all five are activated by the local `STAT_TYPE=all` setting.

## 7. Open PostgreSQL and check initialization

For a GUI client: host `localhost`, port `5432`, database `statsdb`, username/password
from active `POSTGRES_USER` / `POSTGRES_PASSWORD`. Or open psql without manually
copying the password:

```bash
docker exec -it stats-postgres sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Run:

```sql
SET TIME ZONE 'UTC';
SELECT current_database(), current_user;
SELECT version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;
\d+ ltestats
\d+ netstats
```

Expect statsdb, successful Flyway migrations including V8 LTE and V9 netstats,
RANGE partitioning on ts_device and primary key (ts_device, uid).

On first startup with an empty data volume, PostgreSQL creates the configured
database/owner and runs `docker/postgres-init/10-read-only-user.sql` to create the
read-only role/grants. Writer then applies Flyway migrations and precreates daily
partitions before ingestion. Existing volumes retain data; first-initialization
scripts do not rerun on every restart. Starting infrastructure alone does not run
Writer's migrations or partition bootstrap.

## 8. Check required daily partitions for all five types

In the same UTC psql session, count attached partitions:

```sql
SELECT p.relname AS parent, count(*) AS partitions
FROM pg_inherits h
JOIN pg_class p ON p.oid = h.inhparent
JOIN pg_class c ON c.oid = h.inhrelid
WHERE p.relname IN ('lorastats','sysstats','omsstats','ltestats','netstats')
GROUP BY p.relname ORDER BY p.relname;
```

With current defaults, the required window is today minus 90 days through today
plus seven days inclusive: 98 days. Existing tables may additionally have older
partitions until scheduled retention runs. Check missing required days directly:

```sql
WITH types(name) AS (
  VALUES ('lorastats'),('sysstats'),('omsstats'),('ltestats'),('netstats')
), days AS (
  SELECT generate_series(current_date - 90, current_date + 7,
                         interval '1 day')::date AS day
)
SELECT t.name, d.day AS missing_day
FROM types t CROSS JOIN days d
WHERE NOT EXISTS (
  SELECT 1 FROM pg_inherits h
  JOIN pg_class p ON p.oid = h.inhparent
  JOIN pg_class c ON c.oid = h.inhrelid
  WHERE p.relname = t.name
    AND c.relname = t.name || '_' || to_char(d.day, 'YYYY_MM_DD')
)
ORDER BY t.name, d.day;
```

Expected: **zero rows**. Inspect LTE/netstats child bounds:

```sql
SELECT p.relname AS parent, c.relname AS partition,
       pg_get_expr(c.relpartbound, c.oid) AS bounds
FROM pg_inherits h
JOIN pg_class p ON p.oid = h.inhparent
JOIN pg_class c ON c.oid = h.inhrelid
WHERE p.relname IN ('ltestats','netstats')
ORDER BY p.relname, c.relname;
```

Each name's date should match its lower bound, at 00:00 UTC. Its upper bound
should be the following day's 00:00 UTC. Inspect a real child with `\d+ CHILD_NAME`.
Expect three indexes: (ts_device, uid) primary key; (tenant, uid); and
(tenant, clsbox, ts_device DESC, uid DESC).

## 9. Verify a test record's actual partition and duplicate count

Replace the literal UID below with the resolved Postman UID. Run once before and
once after replaying the exact original body through the external door:

```sql
SELECT uid, ts_device, tableoid::regclass AS actual_partition
FROM ltestats WHERE uid = 'PASTE_LTE_UID';

SELECT count(*) FROM ltestats WHERE uid = 'PASTE_LTE_UID';

SELECT uid, ts_device, tableoid::regclass AS actual_partition
FROM netstats WHERE uid = 'PASTE_NET_UID';

SELECT count(*) FROM netstats WHERE uid = 'PASTE_NET_UID';
```

Expect one row per UID and partition TYPE_YYYY_MM_DD matching ts_device's UTC date.
The duplicate replay should keep counts at one. Exit psql with `\q`.

## 10. Check scheduled retention separately

In Prometheus query `retention_last_sweep_timestamp_seconds`. NaN means no completed
sweep has been observed in the current process lifecycle. The deployed schedule is
03:30 UTC daily (09:00 IST), with 30-second type stagger slots. Startup creates
partitions but does not run the destructive retention sweep.

The 9 October audit found 10 older partitions each for LoRa/sys/OMS, none for
LTE/netstats. After a scheduled pass, check the gauge and partition catalog again;
do not equate complete partition creation with successful cleanup. Inspect Writer
maintenance logs for failures if expired partitions remain after the pass.

The completed automated audit and sanitized aggregate evidence are linked from
[the verification report](LTE_NETSTATS_VERIFICATION_REPORT_2026-10-09.md).
