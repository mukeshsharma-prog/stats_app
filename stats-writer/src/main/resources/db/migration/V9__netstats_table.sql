-- netstats: additive daily partitioned table (STA-21A/21B/21C, ADR-003).
-- Identifiers retain the current wire contract for local development; D-12 remains a release gate.
CREATE TABLE netstats (
    uid text NOT NULL,
    tenant text NOT NULL,
    maintenant text NOT NULL,
    clsbox text NOT NULL,
    ts_server timestamptz NOT NULL,
    ts_device timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    record_version smallint,
    schema_version smallint NOT NULL,
    passthrough jsonb,
    data_extra jsonb,
    timespan bigint NOT NULL,
    interfaces jsonb NOT NULL,
    CONSTRAINT netstats_interface_map CHECK (
        jsonb_typeof(interfaces) = 'object' AND interfaces ?& ARRAY['eth0', 'wwan']
        AND jsonb_typeof(interfaces->'eth0') = 'object'
        AND jsonb_typeof(interfaces->'wwan') = 'object'
    ),
    PRIMARY KEY (ts_device, uid),
    CONSTRAINT netstats_clsbox_canonical_hex CHECK (clsbox ~ '^[0-9A-F]{12}$')
) PARTITION BY RANGE (ts_device);

CREATE INDEX idx_netstats_tenant_clsbox_device_uid
    ON netstats (tenant, clsbox, ts_device DESC, uid DESC);
CREATE INDEX idx_netstats_tenant_uid ON netstats (tenant, uid);

COMMENT ON COLUMN netstats.passthrough IS 'Unread envelope members preserved without timestamp normalization.';
COMMENT ON COLUMN netstats.data_extra IS 'Unknown data members preserved verbatim (D14).';
COMMENT ON COLUMN netstats.interfaces IS 'Dynamic interface map; known counters are checked signed-64-bit JSON integers, returned as wire strings.';
