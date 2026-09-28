package com.zenner.datahub.stats.api.query;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.common.model.StatType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * {@code sysstats} (V6). Three wire types in one payload, and the contract returns each
 * in the representation it arrived in: {@code runable} is the gateway's own {@code "2/139"}
 * string, the loads are doubles, and {@code timespan} — stored as {@code bigint} after the
 * Writer's range check (DL-148) — goes back as the string {@code "300"}, because on this
 * type, unlike the other four, that is what the wire sends (ADR-007 §5).
 */
public final class SysstatsReader implements StatReader {

    private static final List<String> COLUMNS =
            List.of(
                    "memfree",
                    "uptime",
                    "idletime",
                    "load1",
                    "load5",
                    "load15",
                    "runable",
                    "temp",
                    "rtt",
                    "wan_if",
                    "rebootcause",
                    "timespan");

    @Override
    public StatType type() {
        return StatType.SYSSTATS;
    }

    @Override
    public List<String> dataColumns() {
        return COLUMNS;
    }

    @Override
    public void putData(ResultSet row, ObjectNode data) throws SQLException {
        StatReader.putLong(row, "memfree", data);
        StatReader.putDouble(row, "uptime", data);
        StatReader.putDouble(row, "idletime", data);
        StatReader.putDouble(row, "load1", data);
        StatReader.putDouble(row, "load5", data);
        StatReader.putDouble(row, "load15", data);
        StatReader.putText(row, "runable", data);
        StatReader.putLong(row, "temp", data);
        StatReader.putLong(row, "rtt", data);
        StatReader.putText(row, "wan_if", data);
        StatReader.putText(row, "rebootcause", data);
        StatReader.putLongAsString(row, "timespan", data);
    }
}
