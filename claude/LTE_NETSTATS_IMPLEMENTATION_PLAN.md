# LTE and network stats — end-to-end implementation plan

**Prepared:** 8 October 2026.

**Scope:** complete `ltestats` (T-034) and `netstats` (T-037), from HTTP acceptance through RabbitMQ, Writer validation, PostgreSQL, secured reads, operational verification and packaged delivery.

**Status:** plan only; implementation and runtime verification have not been performed.

## 1. What already exists and what is missing

The three working types are the reference: `sysstats` supplies the additive consumer/configuration pattern, and `omsstats` demonstrates its replication. Extend those patterns with the two new payload mappings.

| Layer | Current state for LTE and net stats | Planned work |
|---|---|---|
| Type identity and schemas | Both types are in `StatType`; schemas and production, boundary, invalid and tenant fixtures exist | Audit the mappings and add missing cases; keep the existing type identities |
| HTTP Input | Both types use the shared envelope gate and publisher; `/internal/stats` and `/external/stats` already exist | Prove byte-identical publication and confirmed acceptance for each type on both doors |
| AMQP definitions | Shared `AmqpTopology` defines main, three retry and DLQ resources for all five types; Input declares them | Add each Writer's declarations/listener from those definitions and verify declaration parity |
| Writer | No LTE/net processor, consumer or persistence writer | Add one complete consumer slice per type |
| Database | Migrations V1–V7 create LoRa, sys and OMS tables only | Add two new versioned migrations, including partitioning and indexes |
| Read API | Shared secured endpoints exist; `StatReaders` has no LTE/net reader | Add and register readers; populate every existing read operation |
| Operations and delivery | Retention, retry/redrive, metrics, dashboards, Compose and offline packaging exist | Exercise them for both types; extend demo, Postman and handover evidence |

The API currently returns empty pages and 404 by UID for these types because they have no tables. A new table and its API reader must land together: `StatReadersCoverEveryTableTest` intentionally fails otherwise.

## 2. Decisions and readiness checks

### Recorded decisions that still need resolution

| Item | Required answer/evidence | Owner and consequence |
|---|---|---|
| **LTE: D-12 P3** | Raw or masked IMSI; where masking applies; whether `lac`/`cid` receive the same treatment. Specify the stored and returned representations | Security/compliance. The repository explicitly gates T-034 merge on these answers; this plan does not assume an answer |
| **Net: Q15g** | Confirm producer/forward route, whether netstats is active, and obtain representative healthy, down-WWAN and additional-interface messages if available | ZC/Hasan. The current status plan gates T-037 because the live datastore previously contained zero netstats records. The request establishes implementation scope, but does not supply production delivery evidence |
| **Net hybrid mapping** | Record exactly how the interface map stores checked counters and restores wire strings | Engineering. Follow ADR-003 D2; reconcile older wording about BIGINT columns with the dynamic JSONB map before coding |

If fresh netstats traffic is unavailable, supplied fixtures can prove the software path, but the handover must distinguish that from verification of the real upstream feed. LTE and netstats have independent gates: netstats must not inherit LTE's IMSI dependency.

If LTE uses a keyed hash, record the key source, stability across replicas, rotation/version policy and API representation. A Writer transformation alone does not remove raw identifiers from Input messages, retry queues or DLQs. Cover those copies, `passthrough`, `data_extra` and backups in the policy; never retain the raw value in an extra JSON column as a workaround. Any transformed API shape needs an explicit contract update.

**Initial checks:** establish a current green baseline; verify T-033 outage/recovery evidence; inventory current schema fixtures and contracts; reserve migration versions against the branch at implementation time. Provisionally use V8 for LTE and V9 for netstats if the work lands in that order. Never renumber an applied migration if sequencing changes.

### Contract gaps found during planning

1. **LTE mixed shape:** the documentation calls `measured` and `unknown-technology` mutually exclusive, but the measured schema allows unknown properties. A complete measured payload plus `etxt` currently validates; the existing mixed fixture covers a partial measurement only. Add the complete mixed negative case and align the schema with ADR-007 B1, preserving other unknown members on measured records. Mirror the change in OpenAPI and both validator suites.
2. **Net overflow:** `invalid/netstats-counter-overflow.json` contains a 20-digit value rejected by the schema. Add a **schema-valid 19-digit** counter such as `9223372036854775808` to prove Writer range rejection, and accept `9223372036854775807`. Keep schema rejection and mapping rejection as separate expectations.
3. **Stale checklist wording:** `claude/SKILLS.md` still says payload errors return HTTP 400 in parts of its add-type procedure. ADR-007 §5 and the implemented validator govern: Input rejects envelope errors; the Writer dead-letters payload errors after confirmed acceptance. Correct that wording with the implementation documentation.

## 3. Shared behavior both types must inherit

```text
POST /internal/stats or /external/stats
  -> envelope/auth/limits gate
  -> persistent, mandatory, byte-identical publish on app_stats_input
  -> publisher confirm -> configured 200/202 with status=accepted and traceId
  -> app_statsstore.<type>
  -> dedicated listener -> payload gate -> retention guard -> row mapping
  -> committed INSERT or metered duplicate -> manual ACK
  -> failure -> confirmed retry/DLQ handoff -> ACK original
  -> tenant-scoped GET -> type reader -> canonical JSON response
```

- Acceptance means broker handoff. It does not assert database persistence.
- Preserve Unix-second timestamps, `tenant`, `maintenant` and unread envelope members. Never rewrite `ts.device` or use `ts.converter` to normalize it.
- Select shape structurally; store `version` without using it as a discriminator.
- Uppercase `clsbox` in the Writer, backed by the database CHECK.
- Keep `PRIMARY KEY (ts_device, uid)` and `ON CONFLICT (ts_device, uid) DO NOTHING`; count duplicate suppression. Do not add tenant to the key as part of this work.
- Use `WireNumbers` for numeric coercion and reject overflow before conversion. Mapping errors are permanent `unmappable` failures, with no transaction opened.
- Validate payload before retention handling. An expired but malformed record belongs in the DLQ; a valid expired record is skipped, metered and ACKed.
- Retain the three configured retry tiers, confirmed onward handoff, inert DLQ without TTL and scoped manual redrive behavior.
- Never log LTE/net payloads, even at DEBUG (adopted D-12 P1). Use `SafeFailure` and `WriterLogContext`; avoid exception messages, throwable logging and duplicate MDC keys.
- Use the shared partition maintenance over the **whole retention window**, plus future days, with per-type advisory locks. No per-type scheduler copy.

## 4. LTE vertical slice

### 4.1 Payload and database mapping

Create `ltestats` with the common V7 envelope columns: `uid`, `tenant`, `maintenant`, `clsbox`, `ts_server`, `ts_device`, `received_at`, `record_version`, `schema_version`, `passthrough` and `data_extra`.

| Wire member | Proposed column/handling | Read representation |
|---|---|---|
| `rssi`, optional `rsrp`, optional `rsrq` | Nullable BIGINT; signed digit strings via `WireNumbers` | Decimal strings; absent optional values omitted |
| Optional `timespan` | Nullable BIGINT; unsigned digit string | Decimal string, or omitted |
| `access_Technology` | TEXT `access_technology`; explicitly map the capital `T` | Exact `access_Technology` key |
| `operator`, `plmn` | TEXT; preserve identifiers and leading zeroes | Strings |
| `imsi` | Representation determined by D-12; TEXT if raw identifiers are approved | Approved raw/masked form; never numeric |
| `lac`, `cid` | Representation determined by D-12; prefer original hexadecimal TEXT if raw storage is approved | Preserve hex spelling/width if approved; no guessed decimal conversion |
| `etxt` | Nullable TEXT, populated only for unknown-technology shape | `{"etxt": "…"}` without manufactured measurements |
| Unknown measured-shape members | `data_extra`, subject to the approved sensitivity policy | Preserved JSON members |

All measured columns must be nullable at the column level because an `etxt` record is valid. Add a table constraint enforcing the two admissible row forms: required measured fields with no `etxt`, or `etxt` with no measured fields. Define that constraint against the final D-12 representation. Optional signals and timespan are never defaulted to zero.

Use daily `RANGE (ts_device)` partitioning and the V7 indexes `(tenant, clsbox, ts_device DESC, uid DESC)` and `(tenant, uid)`, plus the canonical `clsbox` CHECK. Correct the schema comment suggesting INTEGER parsing if BIGINT is adopted, so documentation and range tests agree.

### 4.2 Writer and consumer

Add siblings under `stats-writer/.../consumer/`:

- `LtestatsConsumerDescriptor`, `LtestatsConsumerProperties`, `LtestatsDeploymentCondition`.
- `LtestatsConsumerConfiguration`, `LtestatsConsumer`, `LtestatsMessageProcessor`.
- `persistence/LtestatsBatchWriter` and the LTE migration.

The descriptor owns identity, property prefix and writer bean name. Qualify the `StatsBatchWriter` injection. Reuse the shared processor selector, exchanges, gates, transaction/result contract and failure router. Observe queue age at listener entry. Declare main/retry/DLQ resources using `AmqpTopology`, including the retry return binding.

Configure `stats.consumer.ltestats.{concurrency,prefetch,shutdown-timeout}` with the reference starting values **1 / 500 / 5s**, externally overridable. Prove `STAT_TYPE=ltestats` selects its listener and processor, other selections exclude them, and development `all` starts without bean ambiguity. Production continues to forbid `all`.

### 4.3 API and LTE acceptance

Add `LtestatsReader` and its bean in `StatsApiConfiguration`. Use `putLongAsString` for numeric-string members; omit SQL NULLs and restore the exact wire keys. Implement the approved sensitivity behavior at the chosen boundary.

Prove measured LTE, 2G without RSRP/RSRQ, omitted timespan and `etxt`-only records persist and read back. Prove partial/full mixed shapes, malformed numeric strings and schema-valid overflow reach only the LTE DLQ. Test maximum/minimum supported numeric values, unknown measured members, lowercase clsbox, passthrough members, duplicate replay and both retention boundaries.

## 5. Netstats vertical slice

### 5.1 Hybrid payload and database mapping

Create `netstats` with the same envelope columns, constraints, daily partitioning and two indexes. Add **`timespan BIGINT NOT NULL`** and **`interfaces JSONB NOT NULL`**, plus `data_extra` for unknown non-interface top-level data. Require an object map containing `eth0` and `wwan`.

**Proposed engineering representation:** store known counter fields in the interface JSONB as integer JSON values after signed-BIGINT range validation; restore their decimal strings in `NetstatsReader`. This preserves ADR-003's conversion-on-write and dynamic interface map. It does not create a fixed column set or one child row per interface. Document this decision and reconcile descriptions implying a BIGINT SQL column for every dynamic interface counter.

- `timespan` is a JSON integer, not a string; validate its range through `WireNumbers`.
- For each `eth0`, `wwan` and name matching the schema's interface pattern, require `tx`/`rx`, then check them with unsigned `WireNumbers`.
- Apply the same checks to optional `mtu`, `fail_count`, `last_forced_reset` and `last_powercycle` on **every** recognized interface. One bad nested counter rejects the record atomically.
- Require only `tx`/`rx` per interface. A down WWAN without `ip`, `gw`, `mask` or `dns` is valid.
- Preserve optional `apipa`, DNS arrays, `driver`, `plmn`, `plmn_list`, `need_init` and unknown nested members, including their wire types. PLMN remains an identifier string.
- Preserve additional interfaces without a migration. Separate them from unknown non-interface top-level members using the existing schema pattern, excluding `timespan`.
- Retain IPv4-only validation in V1, including IPv6 rejection when an address is present. Do not add guessed defaults or drop unknown members.
- Read/write nested values with integer precision; never use floating point for counters. Reconstruct only known counters as strings; unknown member values remain unchanged.

### 5.2 Writer, consumer and API

Add `NetstatsConsumerDescriptor`, `NetstatsConsumerProperties`, `NetstatsDeploymentCondition`, `NetstatsConsumerConfiguration`, `NetstatsConsumer`, `NetstatsMessageProcessor`, `NetstatsBatchWriter`, the net migration and `NetstatsReader`.

Use the same shared behavior and configuration tests as LTE, with `stats.consumer.netstats.*` and `STAT_TYPE=netstats`. Register the reader alongside the migration. Its selected columns include `timespan` and `interfaces`; rebuild `data` by flattening the map under the original interface names, then let `RecordAssembler` merge `data_extra`.

### 5.3 Netstats acceptance

Prove supplied sample, healthy production sample, down-WWAN, no-APIPA, boundary values and extra-interface fixtures persist and round-trip. Add unknown top-level and nested member cases, multiple extra interfaces and nested counter overflow on an extra interface. Prove missing WWAN, missing tx/rx, non-digit counters, IPv6 and numeric overflow fail at the correct gate. Verify string types and exact integer precision on API output, including 19-digit counters.

## 6. API completeness and integration proof for both types

Use the existing shared controller, query builder, cursor and security code. Each reader must support:

| Operation | Required proof |
|---|---|
| `GET /v1/stats/{type}` | Stored records, supported time filters, page limits, stable `(ts_device DESC, uid DESC)` ordering and cursor continuation |
| `GET /v1/stats/{type}/{clsbox}/{from}` | Device filtering, case normalization and open-ended semantics; include near-future device timestamps within Input tolerance |
| `GET /v1/stats/{type}/{clsbox}/{from}/{to}` | Supported boundary semantics and pagination; no tenant-wide window cap imposed on device reads |
| `GET /v1/stats/{type}/{uid}` | Owning tenant can read; foreign and missing UIDs produce the same 404 |

Use tenant pairs where `tenant != maintenant`, the minol-style shape and a moved gateway. Foreign device/list requests return an empty 200. Check invalid tokens/roles/tenant claims, tampered and cross-type/cross-tenant cursors, and non-GET 405 behavior. Run as `stats_ro` with real migrations and the shipped grants; verify new tables/partitions are readable and writes are denied. Keep the API free of broker dependencies.

Update the existing `StatsReadIntegrationTest` cases describing these types as unbuilt: retain empty-table behavior, and add seeded data proving the registered reader returns records. Extend isolation and pagination suites to both types. Verify `StatReadersCoverEveryTableTest` covers all five migrated tables.

Add real HTTP → RabbitMQ → PostgreSQL → authenticated GET tests for each new type. Use unique records with dates inside the retention window, bounded polling rather than fixed sleeps, and matching trace IDs. Assert payload field values/types, owning-tenant visibility and duplicate counters. Confirm both Input doors retain `{"status":"accepted","traceId":"…"}` only after publication succeeds.

## 7. Failure, lifecycle and operational verification

For **each** new type, prove:

1. Envelope errors are 400 with no publication; broker failure/timeout/unroutable publication is 503 without acceptance.
2. Schema/mapping errors immediately reach the correct DLQ, preserve failure evidence safely and do not open a transaction for mapping errors.
3. Database outage and commit failure withhold successful ACK; retry tiers return to the correct main queue and exhaustion reaches the type's DLQ.
4. An unconfirmed retry/DLQ handoff leaves the original unacknowledged. A commit/ACK-gap replay produces one row and metered suppression.
5. Poison LTE traffic does not stall net, LoRa, sys or OMS; poison net traffic does not stall LTE or the existing types.
6. Startup pre-creates current, in-window historical and future partitions before consumption; retention cutoff behavior and per-type overrides agree with the shared guard.
7. Restart and graceful shutdown retain committed results and recover unfinished deliveries. Main/retry/DLQ declarations remain durable quorum queues; DLQs have no TTL.
8. Authorized scoped redrive preserves bytes/metadata, has the existing single-attempt failure behavior, and expired redrive increments `records_expired_on_arrival`.

Assert exported per-type metrics from startup and after events: consumed, persisted, duplicate, rejected/unmappable, retry, DLQ, write failures, processing duration, queue age/depth, consumer availability and retention health. Check dashboards/alerts include both labels; shared broker gauges remain aggregated with `max`. Existing metrics support these types generically, so add assertions before considering any shared-code change.

Run mixed traffic across all five types, then a focused LTE/net load check with representative payload sizes and additional interfaces. Report sustained rate, drain time, queue age, p95/p99 processing/read latency and errors. Use the existing ~267/s/type nominal rate and 13,333/s aggregate burst headroom as configured targets; measure capacity rather than extrapolating the earlier LoRa-only drill. Match Input instance rate limits to any burst run.

## 8. Files and checks

| Area | Files to add or update |
|---|---|
| Per-type implementation | The consumer, persistence, migration and reader files listed above; matching migration, column-mapping, writer, processor, listener, activation and topology tests |
| Wiring/config | `StatsApiConfiguration.java`, Writer `application.properties`, configuration metadata if needed, `.env.example` documentation of tuning |
| Architecture guards | Add justified descriptor **and reader** entries to `StatTypeFanOutTest`; writer entries to `VersionIsNotADiscriminatorTest`. Use `Map.ofEntries` when the expanded exception map exceeds `Map.of`'s ten-entry limit. Retain the coercion/logging/broker-isolation guards |
| Schemas/contracts | LTE mixed-shape fix and numeric comment, fixture `MANIFEST.md`, catalogue/parity tests, both OpenAPI contracts where affected; AMQP contract and Writer metrics status text |
| API tests | Reader/table guard, read integration, tenant isolation and pagination; real migration/grant support already exists |
| Operational tests | Existing retry/redrive/retention/management suites and any type assumptions; focused all-five failure isolation and new-type load evidence |
| Demonstration/delivery | `docker/demo.sh`, Postman collection/environment/README, relevant Compose configuration, packaging smoke checks and handover guide |
| Working records | `claude/DECISIONS.md`, `PROJECT_STATUS_AND_PLAN.md`, `FLOW.md`, `CLAUDE.md`, stale checklist wording and README. Update T-034/T-037 in the external task tracker when implementing; this plan does not edit the workbook |

Use the current working tree as the baseline; it already contains Input acknowledgement, packaging and handover changes. Preserve those changes. Do not modify existing LoRa/sys/OMS implementations to dispatch the new types.

Verification commands from the repository root, following the current CI workflow:

```bash
python docs/schemas/validate-fixtures.py
python docs/contracts/test_schema_drift.py
python docs/contracts/check_schema_drift.py
./gradlew :stats-common:test :stats-input:test :stats-writer:test :stats-api:test --no-daemon
python ops/grafana/check-dashboards.py
npx --yes @redocly/cli@2.51.2 lint
docs/contracts/build-html.sh
./gradlew build --no-daemon
```

Also run the existing CI `promtool` checks, focused new-type integration tests against real PostgreSQL/RabbitMQ, all-five demo/Postman tests and a fresh offline-bundle smoke test. Schema/contract commands and full builds are implementation checks, not claims of results from this planning task. Request independent review of the new isolation/sensitivity tests before release, consistent with T-028/T-045.

## 9. Recommended execution order and rollout

1. **Preparation:** baseline, resolve/record the decisions in §2, finalize mappings and new fixtures.
2. **LTE slice:** migration + writer + consumer + reader + focused tests in one reviewable change. Complete functional/failure/tenant tests before marking T-034 done.
3. **Net slice:** the equivalent complete change with the hybrid map and interface tests. If LTE policy remains pending, net can proceed once its own gate is resolved; allocate migrations by actual landing order.
4. **Combined verification:** all-five context/declaration tests, cross-type failure isolation, end-to-end reads, retention/replay and capacity evidence.
5. **Delivery:** update docs/Postman/demo; build and smoke-test a new versioned offline bundle from final source. Existing immutable bundles remain historical artifacts.

For a shared rollout, retain existing platform/auth/contract release conditions. New stat slices do not resolve T-068 external-door tenant binding or outstanding contract confirmations. Coordinate migrations and readers before enabling new listeners/upstream traffic. Apply migrations once through the deployment process, confirm partitions and read grants, deploy the matching API, then enable one canary Writer per new type before scaling. Production uses the same Writer image with separate `STAT_TYPE=ltestats` and `STAT_TYPE=netstats` deployments; local Compose may use `all`.

Canary checks: each queue has the intended consumer, a valid message commits and is readable by the owning tenant, a duplicate is suppressed, and invalid payloads reach only the correct DLQ. Monitor backlog, processing/read latency, DLQ and retention counters. On regression, stop new-type listeners/traffic, preserve queues and data, and fix forward; do not drop tables or purge queues as rollback. Keep compatible readers available for data already stored.

## 10. Definition of done

- [ ] Each new type completes confirmed HTTP ingest → dedicated queue → valid committed row → all secured GET operations.
- [ ] Every approved LTE shape and net interface shape works, including degraded modem states; wire types and approved sensitive representations survive reads.
- [ ] Envelope, schema, mapping, overflow, database, retry, DLQ and expiry outcomes have distinct passing evidence.
- [ ] Duplicate replay, ACK ordering, restart/shutdown, retention, tenant isolation and cursor behavior pass for both types.
- [ ] Single-type and local all-type deployments work; all three existing types remain green.
- [ ] Per-type monitoring and all-five demonstration/Postman tests pass; measured capacity and any limits are recorded.
- [ ] Contracts, decision/status records and a new tested delivery bundle match the final implementation.
- [ ] D-12 and Q15g dispositions are recorded; production-feed verification and any remaining release conditions are stated accurately.
