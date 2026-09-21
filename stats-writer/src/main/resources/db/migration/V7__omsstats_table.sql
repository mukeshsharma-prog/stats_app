-- omsstats: the third stat-type table (T-036, DL-153). ADR-003 D1/D2/D7, ADR-004, STA-21A/21B/21C.
--
-- A copy of V6 (sysstats, DL-148), which is the reference shape: every invariant the
-- lorastats table reached across V1..V5 is present from the first row.
--
--   * PRIMARY KEY (ts_device, uid) IN THAT ORDER, scoped per partition (G2, STA-21B);
--     RANGE (ts_device) daily partitions pre-created by the writer over the whole
--     retention window (ADR-003 D1, DL-149 -- nothing per type).
--   * The two ADR-003 indexes, both leading on tenant because the tenant predicate is
--     mandatory on every query (rule 3, STA-35): the covering index for the dominant
--     page and the (tenant, uid) probe for the time-unbounded uid lookup. NOT the
--     (ts_device DESC, uid DESC) index V2 shipped for lorastats -- the pkey already
--     answers that ordering with a backward scan (V3).
--   * passthrough jsonb for the envelope members that are stored and never read. On
--     this type that includes ts.converter, which production sends on every record:
--     accepted and preserved, never acted on -- there is no normalisation column and no
--     normalised timestamp anywhere (ADR-004 as amended by ADR-007 §2).
--   * data_extra jsonb for unknown data members (D14b, V4). Omitting data_extra
--     silently reopens the drop, and no test on this type would catch it.
--   * CHECK (clsbox ~ '^[0-9A-F]{12}$') as the table's own backstop for the one
--     canonical case the Writer normalises to (DL-115, V5). Without it one device
--     splits across two index keys and the API returns a silently partial page.
--
-- Column types follow the wire (ADR-007 §6, SCHEMA_CATALOGUE): all five payload members
-- are integers on the wire and required by the schema -- packet counters, a device
-- count, and timespan, which is an INTEGER here (the string "300" is sysstats' spelling
-- only). Integers are bigint, never integer: int4 overflow is silent corruption and the
-- four bytes are cheap. The Writer refuses a JSON integer wider than bigint before it
-- reaches the column (STA-04C); nothing is truncated on the way in.
--
-- record_version, passthrough and data_extra are nullable on purpose (ADR-003): every
-- fixture written before 29 Aug 2026 omits the members they hold.

CREATE TABLE omsstats (
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
    rx_nr_pkts         bigint           NOT NULL,
    rx_valid_nr_pkts   bigint           NOT NULL,
    rx_crc_bad         bigint           NOT NULL,
    device_count       bigint           NOT NULL,
    timespan           bigint           NOT NULL,
    PRIMARY KEY (ts_device, uid),
    CONSTRAINT omsstats_clsbox_canonical_hex CHECK (clsbox ~ '^[0-9A-F]{12}$')
) PARTITION BY RANGE (ts_device);

CREATE INDEX idx_omsstats_tenant_clsbox_device_uid
    ON omsstats (tenant, clsbox, ts_device DESC, uid DESC);

CREATE INDEX idx_omsstats_tenant_uid
    ON omsstats (tenant, uid);

COMMENT ON COLUMN omsstats.clsbox IS
    'Device identifier, 12 uppercase hex characters. Normalised by the Writer and '
    'constrained here so (tenant, clsbox, ...) cannot split one device across two '
    'keys (DL-115).';

COMMENT ON COLUMN omsstats.passthrough IS
    'Envelope members stored and never read: ts.datastore, ts.converter, ts.expiresOn, '
    'ts.deviceOrig, status, meta. ts.converter is preserved, not applied (ADR-004, ADR-007 §2).';

COMMENT ON COLUMN omsstats.data_extra IS
    'Unknown data members, preserved verbatim (D14/D14b). Never read by the application.';

COMMENT ON COLUMN omsstats.timespan IS
    'Reporting window in seconds. An INTEGER on the wire for this type (ADR-007 §6).';
