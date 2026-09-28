package com.zenner.datahub.stats.api.query;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.common.model.StatType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * {@code omsstats} (V7). Five integer members, all {@code NOT NULL bigint}. There is no
 * converter column and no normalised timestamp to read: {@code ts.converter} is in
 * {@code passthrough} and comes back verbatim with the other envelope members (ADR-004,
 * ADR-007 §2).
 */
public final class OmsstatsReader implements StatReader {

    private static final List<String> COLUMNS =
            List.of("rx_nr_pkts", "rx_valid_nr_pkts", "rx_crc_bad", "device_count", "timespan");

    @Override
    public StatType type() {
        return StatType.OMSSTATS;
    }

    @Override
    public List<String> dataColumns() {
        return COLUMNS;
    }

    @Override
    public void putData(ResultSet row, ObjectNode data) throws SQLException {
        for (String column : COLUMNS) {
            StatReader.putLong(row, column, data);
        }
    }
}
