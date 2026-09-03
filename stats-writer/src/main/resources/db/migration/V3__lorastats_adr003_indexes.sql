-- T-011 correction. ADR-003, "Required table and index direction".
--
-- V2 created (ts_device DESC, uid DESC). That index is unreachable. PRIMARY KEY
-- (ts_device, uid) already builds an equivalent btree, and PostgreSQL answers a
-- DESC/DESC ordering from it with a backward scan, so the planner never selects
-- the V2 index. Measured on PG17 with the real migrations: dropping it leaves
-- "Index Only Scan Backward using lorastats_pkey". It was a second full btree
-- maintained on every insert -- 23.04M rows per type per day (ADR-003, Context)
-- of write amplification buying nothing.
--
-- The two indexes ADR-003 actually specifies were never created:
--
--   * The covering index for the dominant query pattern (STA-21C). Without it a
--     tenant-scoped page is a pkey backward scan with a discard filter: measured
--     2,444 rows read and thrown away to return 50, on one day of only 200k rows
--     and 50 tenants. A production partition holds ~23M.
--
--   * The (tenant, uid) probe for GET /v1/stats/{type}/{uid}, which supplies no
--     time bound and so cannot prune partitions. Without it the planner walks the
--     pkey on a non-leading column: measured 989 buffer hits for one row on ONE
--     partition, and the endpoint touches all 90 live ones.
--
-- tenant leads both, because the tenant predicate is mandatory on every query
-- (claude/CLAUDE.md rule 3, STA-35). Declared on the parent; PostgreSQL propagates
-- both to every existing and future partition.
--
-- CONCURRENTLY is not available on a partitioned parent. These tables are empty at
-- this migration, so the ACCESS EXCLUSIVE lock is free. Against a populated cluster
-- this must run in a maintenance window, or be applied per partition concurrently
-- and then attached -- see DL-093.

DROP INDEX IF EXISTS idx_lorastats_tsdevice_uid;

CREATE INDEX idx_lorastats_tenant_clsbox_device_uid
    ON lorastats (tenant, clsbox, ts_device DESC, uid DESC);

CREATE INDEX idx_lorastats_tenant_uid
    ON lorastats (tenant, uid);
