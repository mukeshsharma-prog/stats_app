# PostgreSQL-Only Implementation Baseline

**Status:** `PENDING EXACT VERSION AND CAPACITY APPROVAL`  
**Decision:** PostgreSQL is the only V1 persistence implementation. Do not create MongoDB repositories, MongoDB Docker services, MongoDB indexes, or MongoDB alternative code.

## 1. Confirmed platform direction

The current answer from Hasan is: **use PostgreSQL only**. The three application modules each produce one Docker image. The database remains outside the application images and is provided as an environment dependency.

| Component | Deployment | Database responsibility |
|---|---|---|
| `stats-input` | One Docker image; horizontally scalable | No database access. |
| `stats-writer` | One Docker image; horizontally scalable with per-type consumer controls | The only component with write access. |
| `stats-api` | One Docker image; horizontally scalable | Read-only PostgreSQL access. |
| PostgreSQL | Managed/local infrastructure | One table per stat type; partitioning, indexes, retention, backup/restore. |
| RabbitMQ | Managed/local infrastructure | Exchange, per-type queues, retries, and DLQs. |
| Keycloak | Managed/local infrastructure | API authentication/roles/tenant claims. |

## 2. Data model direction

Use one PostgreSQL table per approved stat type: `lorastats`, `ltestats`, `sysstats`, `omsstats`, and `netstats`. Use a shared common-column pattern and type-specific columns or a carefully approved `jsonb` payload. The choice between fully typed columns and `jsonb` must be made against the dominant query requirements and 115.2 million-record/day maximum scope.

> **SUPERSEDED by ADR-003.** The model below is the original draft and is
> retained for provenance. Two parts of it are **not creatable** under the
> approved partitioned design:
>
> * `PRIMARY KEY (uid)` — PostgreSQL requires a unique index on a partitioned
>   table to include the partition key. It becomes `PRIMARY KEY (ts_device, uid)`.
> * `retention_at` — dropped, because the partition boundary is the retention
>   mechanism and a column would be a second source of truth.
>
> `data jsonb` also no longer applies to the four stable types, which use typed
> columns. See `docs/architecture/adr/ADR-003-Retention-and-Indexes.md` for the
> approved shape.

Original V1 starting model (superseded):

```sql
CREATE TABLE <stat_type> (
    uid             text        NOT NULL,
    tenant          text        NOT NULL,
    maintenant      text        NOT NULL,
    gateway         text        NOT NULL,
    ts_processor    timestamptz NOT NULL,
    ts_device       timestamptz NOT NULL,
    retention_at    timestamptz NOT NULL,
    data            jsonb       NOT NULL,
    received_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (uid)  -- NOT CREATABLE when partitioned; see ADR-003, use (ts_device, uid)
);
```

This is a **design draft**, not a final migration. The team must decide whether each type-specific field should be first-class SQL columns for query/index efficiency or remain in `jsonb`. Since the dominant query pattern is tenant, gateway, and `ts.device`, those fields must be represented/indexable without JSON path scans.

## 3. Index proposal

The minimum read-path index for each table is:

```sql
CREATE INDEX idx_<stat_type>_tenant_gateway_device
    ON <stat_type> (tenant, gateway, ts_device DESC, uid DESC);
```

Retention support requires a strategy selected for PostgreSQL. Options include scheduled batched deletion:

```sql
DELETE FROM <stat_type>
WHERE retention_at < now()
  AND uid IN (
      SELECT uid
      FROM <stat_type>
      WHERE retention_at < now()
      ORDER BY retention_at
      LIMIT :batch_size
  );
```

At the clarified volume, ordinary unpartitioned deletion may create unacceptable table/index bloat. PostgreSQL time partitioning by `ts.device` or `retention_at` should be evaluated immediately. Partition detach/drop is operationally different from row deletion and needs an ADR, migration plan, lock/transaction policy, and monitoring.

## 4. Volume correction and sizing warning

The answer states 160,000 gateways report every 10 minutes for every stat. The correct average is:

| Scope | Average records/day | Average records/second |
|---|---:|---:|
| One stat type | 23,040,000 | 266.67 |
| Four types | 92,160,000 | 1,066.67 |
| Five types including `netstats` | 115,200,000 | 1,333.33 |

The reported formula included `*10*6*24`; the `10` must not be multiplied because ten minutes means six reports per hour. If gateways synchronize their reporting schedule, peak burst rate will be much higher than these averages. PostgreSQL, RabbitMQ, network, and Docker deployment sizing must use a measured burst profile.

## 5. Required PostgreSQL decisions before production code

Resolved in `docs/architecture/adr/ADR-003-Retention-and-Indexes.md` unless noted.

| Decision | Why it matters | Status |
|---|---|---|
| Exact PostgreSQL version/managed service | Determines partitioning/features, drivers, operational policy. | **PostgreSQL 17** |
| Typed columns vs `jsonb` per type | Determines write cost, query/index behavior, schema evolution. | **Typed for four types; hybrid for `netstats`** (ADR-003 D2) |
| Table partition key and granularity | Required to control retention and table/index bloat at this volume. | **`RANGE (ts_device)`, daily** (ADR-003 D1) |
| Retention job ownership and schedule | `ts.device` is the approved business basis; deletion behavior must be safe and observable. | **`stats-writer` + advisory lock, daily off-peak** (ADR-003 D3) |
| Batch size and transaction boundaries | Needed to balance throughput, locking, retry precision, and DLQ behavior. | **Partition drop; batch values retained for fallback only** (ADR-003 D4) |
| Connection pools and writer concurrency | Must be capacity-tested against PostgreSQL and per-type queues. | PENDING — Phase 5 load test |
| Backup/PITR/restore targets | Required for production data recovery. | **ESCALATED** — needs RPO/RTO from product + ops (ADR-003 D5) |
| Migration tool | Select Flyway, Liquibase, or platform standard; migrations are versioned and reviewed. | **Flyway** (ADR-003 D6) — final; Q1a closed 18 Aug 2026 with no reference project to override it |
| JSONB retention/data extraction policy | Needed if payload remains semi-structured. | Applies to `netstats` only. Q4a is answered, but the `jsonb` interface map stays: fixed `eth0_*`/`wwan_*` columns would silently drop an unexpected interface such as `eth1`, which Q4a explicitly permits (ADR-003 D2) |
| `uid` uniqueness scope | A global unique index is impossible on a partitioned table. | **`(ts_device, uid)`** — column order per G2; STA-21B and STA-25A amended 24 Aug 2026. Suppressed duplicates are metered, not silent |
| Late-arrival policy | A record can arrive with no live partition to land in. | **PROPOSED** — reject and meter; needs product sign-off |

## 6. PostgreSQL implementation rules for Claude

Claude must not introduce `MongoRepository`, MongoDB driver dependencies, Mongo Compose services, TTL-index code, `_id` semantics, or Mongo-specific terminology. Use PostgreSQL-compatible Spring Data/JDBC/JPA conventions only after ADR-001 and the exact persistence design are approved. All schema changes must be versioned migrations and tested against a real PostgreSQL container in integration tests.
