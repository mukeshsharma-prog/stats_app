package com.zenner.datahub.stats.api.query;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.common.model.StatType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * {@code lorastats} (V1, V4, V5). Every counter is {@code bigint} and returned as an
 * integer: the Writer normalised all three wire dialects into these columns,
 * so no prefixed name and no quoted counter can appear in a response, and the dialect a
 * record arrived in is neither stored nor returned.
 */
public final class LorastatsReader implements StatReader {

    private static final List<String> COLUMNS =
            List.of(
                    "rx_nr_pkts",
                    "rx_crc_missing",
                    "rx_crc_bad",
                    "tx_success",
                    "tx_failed",
                    "tx_peek",
                    "tx_peek_index_zero",
                    "tx_override",
                    "tx_busy",
                    "tx_dequeue_error",
                    "tx_type_beacon",
                    "chip_comm_error",
                    "util_fopen_failed",
                    "rx_short_pkt",
                    "fsk_received",
                    "rx_type_arol",
                    "timespan");

    @Override
    public StatType type() {
        return StatType.LORASTATS;
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
