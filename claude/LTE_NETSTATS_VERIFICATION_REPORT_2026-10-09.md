# Stats verification — 9 October 2026

All four requested checks passed for sample processing, API behavior, queue
initialization and partition creation. Live retention cleanup remains an observation
to follow up, as detailed below. No application changes were needed during this audit.

The running `stats-app-bundle` deployment was audited without writing test records
or deleting data. A separate `stats-comprehensive-verification` project initialized
fresh volumes from the v6 offline bundle with generated credentials and no image
pulls. All sample replay and Postman writes went to that disposable project.

## 1. LTE and netstats samples

| Sample variant | Records | Result |
| --- | ---: | --- |
| LTE 4G measurements with rssi/rsrp/rsrq | 998 | Schema, persistence and exact API readback passed |
| LTE 2G measurement without rsrp/rsrq | 1 | Passed; omitted signal fields stayed omitted |
| LTE etxt-only error | 1 | Passed; no measurement fields were added |
| Netstats eth0 + wwan + timespan | 1,000 | Schema, persistence and exact API readback passed |

LTE measured fields were `access_Technology`, `cid`, `imsi`, `lac`, `operator`,
`plmn`, `rssi`, and, for the 998 4G records, `rsrp`/`rsrq`. All observed LTE payload
values were strings; this export contained no LTE `timespan`.

Every netstats record contained `eth0` fields `dns`, `gw`, `ip`, `mask`, `rx`, `tx`;
`wwan` contained these plus `driver` and `mtu`. Counters/MTU remained strings,
DNS remained a string array, and root `timespan` remained an integer.
`fail_count`, `last_forced_reset` and `last_powercycle` were absent from this corpus;
sample coverage does not imply those optional fields were exercised by these exports.
The full automated suite separately covers additional contract variants and errors.

All 2,000 original records passed schema and numeric range checks. Each was sent
through `POST /internal/stats`, persisted through RabbitMQ/Writer, and retrieved by
UID. Complete response envelopes matched expected keys, JSON types and values,
including both original timestamps. Isolation changed only UID, tenant and
maintenant; the datastore export `_id` was removed, and clsbox was compared in its
canonical uppercase form. The original payloads and timestamps were preserved.

The same 2,000 records were replayed through `POST /external/stats`. All received
accepted acknowledgements with matching response/header trace IDs. Writer duplicate
counters increased by exactly 1,000 per type; database counts stayed at 1,000 per
sample type, with no additional rows or pending/retry/DLQ messages.

## 2. API checks

All four read routes were exercised for **each of the five stat types**:

| Method | Route |
| --- | --- |
| GET | `/v1/stats/{type}?from=...&to=...` |
| GET | `/v1/stats/{type}/{uid}` |
| GET | `/v1/stats/{type}/{clsbox}/{from}` |
| GET | `/v1/stats/{type}/{clsbox}/{from}/{to}` |

The isolated project returned stored records on all 20 combinations. Live LoRa,
sysstats and OMS had an alpha-tenant record for positive reads. Live LTE/netstats
had no alpha-tenant record available: list routes correctly returned empty results
and absent UID requests returned 404. Their positive persistence/read checks were
therefore performed in the isolated project using all supplied samples.

Both projects passed anonymous 401, missing-role 403, missing-tenant 403,
unsupported-method 405, invalid-time-bounds 400 and absent-UID 404 checks. Existing
UIDs were concealed from the other tenant with 404. API `/actuator/info` stayed
protected. All three applications' readiness and Prometheus endpoints returned
200; the PostgreSQL exporter returned 200 and all four Prometheus targets were UP.

Packaged Postman acceptance completed **40 requests and 99 assertions, zero failures**.

## 3. RabbitMQ initialization

Both existing and fresh projects had exactly **25 application queues**:
five main queues, fifteen retry queues and five DLQs. Every queue was durable,
non-exclusive, non-auto-delete and quorum. Each main queue had one active consumer.

Both topic exchanges, input/return/DLQ routing bindings, default-exchange direct
bindings and retry TTLs (30 seconds, 5 minutes, 30 minutes) matched the topology
contract. Retry queues returned to the correct main route; DLQs had no consumer,
expiry or dead-letter forwarding. All queue depths were zero after processing.

## 4. PostgreSQL partitions

All five parent tables used `RANGE (ts_device)` and primary key `(ts_device, uid)`.
The required window was **11 July–16 October 2026 inclusive, UTC**, covering the
90-day cutoff, current day and seven-day precreation horizon: **98 partitions per type**.

| Type | Fresh project | Live project | Missing required days | Live extra older partitions |
| --- | ---: | ---: | ---: | ---: |
| lorastats | 98 | 108 | 0 | 10 |
| sysstats | 98 | 108 | 0 | 10 |
| omsstats | 98 | 108 | 0 | 10 |
| ltestats | 98 | 98 | 0 | 0 |
| netstats | 98 | 98 | 0 | 0 |

Every child had canonical naming, consecutive midnight-to-midnight UTC bounds,
three valid required indexes, and SELECT-only grants for the API database role.
Checked index counts: 1,470 fresh and 1,560 live. All 2,000 sample rows were also
checked through PostgreSQL `tableoid`: each landed in its original device-date
partition. LTE occupied two daily partitions; netstats occupied 34 daily partitions.

**Retention follow-up:** the live LoRa/sys/OMS tables still have 1–10 July partitions
(30 total) outside the current retention window. Writer's last-sweep gauges were
NaN, so no successful sweep had been recorded in its current lifecycle. Its
05:24 UTC startup occurred after the deployed daily 03:30 UTC maintenance time.
Startup ensures partitions; cleanup runs on the schedule. The next scheduled pass
begins **10 October at 03:30 UTC / 09:00 IST**, with 30-second type stagger slots.
Partition creation is verified; live scheduled cleanup completion is not yet
verified. No existing partitions or records were deleted during this audit.

## Automated regression and evidence

`./gradlew build --no-daemon` succeeded. Recorded test results total **1,154 tests,
zero failures, errors or skips**: common 273, Input 78, Writer 536, API 251,
test support 16. Coverage includes topology, payload variants, API security,
partition bootstrap/precreation, retention cutoffs, locking and restore behavior.
Schema verification passed 117 assertions; all five OpenAPI schema projections and
six drift-detector mutation tests passed.

Sanitized aggregate evidence:

- [Live deployment results](verification/2026-10-09/live-results.json)
- [Complete sample replay and fresh initialization results](verification/2026-10-09/sample-results.json)

These artifacts contain no raw sample payloads, device/subscriber identifiers,
tokens or credentials. The disposable project and its volumes were removed after
verification; the existing deployment remained healthy.
