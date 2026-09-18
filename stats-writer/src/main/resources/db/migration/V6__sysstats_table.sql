-- sysstats: the second stat-type table (T-035, DL-148). ADR-003 D1/D2/D7, STA-21A/21B/21C.
--
-- The lorastats table reached this shape across V1..V5 as each rule was learned. A new
-- type gets the whole shape in one migration, and every invariant those five migrations
-- established is present from the first row:
--
--   * PRIMARY KEY (ts_device, uid) IN THAT ORDER, scoped per partition (G2, STA-21B);
--     RANGE (ts_device) daily partitions pre-created by the writer (ADR-003 D1).
--   * The two ADR-003 indexes, both leading on tenant because the tenant predicate is
--     mandatory on every query (rule 3, STA-35): the covering index for the dominant
--     page and the (tenant, uid) probe for the time-unbounded uid lookup. NOT the
--     (ts_device DESC, uid DESC) index V2 shipped for lorastats -- the pkey already
--     answers that ordering with a backward scan (V3).
--   * passthrough jsonb for the envelope members that are stored and never read;
--     data_extra jsonb for unknown data members (D14b, V4). Omitting data_extra
--     silently reopens the drop, and no test on this type would catch it.
--   * CHECK (clsbox ~ '^[0-9A-F]{12}$') as the table's own backstop for the one
--     canonical case the Writer normalises to (DL-115, V5). Without it one device
--     splits across two index keys and the API returns a silently partial page.
--
-- Column types follow the wire (ADR-007 §6, SCHEMA_CATALOGUE): memfree is an integer
-- in kilobytes; uptime, idletime and the three load averages are real numbers; runable
-- is the gateway's own "runnable/total" string and is stored as such rather than split,
-- so the API can return exactly what was received; temp (millidegrees) and rtt are
-- integers that a gateway may omit; wan_if and rebootcause are strings it may omit;
-- and timespan is the STRING "300" on the wire -- the only type where it is -- parsed
-- and range-checked by the Writer into bigint (STA-04C). Integers are bigint, never
-- integer: int4 overflow is silent corruption and the four bytes are cheap.
--
-- record_version, passthrough and data_extra are nullable on purpose (ADR-003): every
-- fixture written before 29 Aug 2026 omits the members they hold.

CREATE TABLE sysstats (
    uid                text             NOT NULL,
    tenant             text             NOT NULL,
    maintenant         text             NOT NULL,
    clsbox             text             NOT NULL,
    ts_server          timestamptz      NOT NULL,
    ts_device          timestamptz      NOT NULL,
    received_at        timestamptz      NOT NULL DEFAULT now(),
    record_version     smallint,
    schema_version     smallint         NOT NULL,
    passthrough        jsonb,
    data_extra         jsonb,
    memfree            bigint           NOT NULL,
    uptime             double precision NOT NULL,
    idletime           double precision NOT NULL,
    load1              double precision NOT NULL,
    load5              double precision NOT NULL,
    load15             double precision NOT NULL,
    runable            text             NOT NULL,
    temp               bigint,
    rtt                bigint,
    wan_if             text,
    rebootcause        text,
    timespan           bigint,
    PRIMARY KEY (ts_device, uid),
    CONSTRAINT sysstats_clsbox_canonical_hex CHECK (clsbox ~ '^[0-9A-F]{12}$')
) PARTITION BY RANGE (ts_device);

CREATE INDEX idx_sysstats_tenant_clsbox_device_uid
    ON sysstats (tenant, clsbox, ts_device DESC, uid DESC);

CREATE INDEX idx_sysstats_tenant_uid
    ON sysstats (tenant, uid);

COMMENT ON COLUMN sysstats.clsbox IS
    'Device identifier, 12 uppercase hex characters. Normalised by the Writer and '
    'constrained here so (tenant, clsbox, ...) cannot split one device across two '
    'keys (DL-115).';

COMMENT ON COLUMN sysstats.data_extra IS
    'Unknown data members, preserved verbatim (D14/D14b). Never read by the application.';

COMMENT ON COLUMN sysstats.timespan IS
    'Reporting window in seconds. Arrives as the STRING "300" on this type only; '
    'range-checked against BIGINT by the Writer before conversion (STA-04C).';
