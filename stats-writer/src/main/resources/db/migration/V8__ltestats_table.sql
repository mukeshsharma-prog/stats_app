-- ltestats: additive daily partitioned table (STA-21A/21B/21C, ADR-003).
-- Identifiers retain the current wire contract for local development; D-12 remains a release gate.
CREATE TABLE ltestats (
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
    rssi bigint,
    rsrp bigint,
    rsrq bigint,
    access_technology text,
    operator text,
    plmn text,
    imsi text,
    lac text,
    cid text,
    timespan bigint,
    etxt text,
    CONSTRAINT ltestats_shape CHECK (
        (etxt IS NULL AND rssi IS NOT NULL AND access_technology IS NOT NULL
         AND operator IS NOT NULL AND plmn IS NOT NULL AND imsi IS NOT NULL
         AND lac IS NOT NULL AND cid IS NOT NULL)
        OR
        (etxt IS NOT NULL AND rssi IS NULL AND rsrp IS NULL AND rsrq IS NULL
         AND access_technology IS NULL AND operator IS NULL AND plmn IS NULL
         AND imsi IS NULL AND lac IS NULL AND cid IS NULL AND timespan IS NULL
         AND data_extra IS NULL)
    ),
    PRIMARY KEY (ts_device, uid),
    CONSTRAINT ltestats_clsbox_canonical_hex CHECK (clsbox ~ '^[0-9A-F]{12}$')
) PARTITION BY RANGE (ts_device);

CREATE INDEX idx_ltestats_tenant_clsbox_device_uid
    ON ltestats (tenant, clsbox, ts_device DESC, uid DESC);
CREATE INDEX idx_ltestats_tenant_uid ON ltestats (tenant, uid);

COMMENT ON COLUMN ltestats.passthrough IS 'Unread envelope members preserved without timestamp normalization.';
COMMENT ON COLUMN ltestats.data_extra IS 'Unknown data members preserved verbatim (D14).';
COMMENT ON COLUMN ltestats.imsi IS 'Identifier string, never numeric. Raw versus masked shared-release policy remains D-12.';
