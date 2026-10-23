package com.zenner.datahub.stats.api.query;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.common.model.StatType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Rebuilds the LTE wire payload, rather than exposing the SQL column names.
 *
 * <p>The 9 October 2026 live export has measured records with {@code rssi},
 * {@code rsrp}, {@code rsrq}, {@code access_Technology}, {@code operator},
 * {@code plmn}, {@code imsi}, {@code lac} and {@code cid}. Signals and identifiers
 * are strings. The SQL column {@code access_technology} maps to the capital-T wire
 * key. A 2G record omits {@code rsrp}/{@code rsrq}; an unregistered modem instead
 * has only {@code etxt}. None of this export's records has {@code timespan}, but
 * earlier accepted records can have it, so it is returned only when stored.
 *
 * <p>{@code imsi} is the one member that does not go back out as stored: it is masked by
 * {@link ImsiMask} on every route. {@code lac}/{@code cid} are returned
 * as reported, by the same decision.</p>
 */
public final class LtestatsReader implements StatReader {

    private static final List<String> COLUMNS = List.of(
            "rssi", "rsrp", "rsrq", "access_technology", "operator", "plmn",
            "imsi", "lac", "cid", "timespan", "etxt");

    @Override
    public StatType type() {
        return StatType.LTESTATS;
    }

    @Override
    public List<String> dataColumns() {
        return COLUMNS;
    }

    @Override
    public void putData(ResultSet row, ObjectNode data) throws SQLException {
        StatReader.putText(row, "etxt", data);
        if (data.has("etxt")) return;
        for (String field : List.of("rssi", "rsrp", "rsrq", "timespan")) {
            StatReader.putLongAsString(row, field, data);
        }
        String technology = row.getString("access_technology");
        if (technology != null) data.put("access_Technology", technology);
        for (String field : List.of("operator", "plmn", "lac", "cid")) {
            StatReader.putText(row, field, data);
        }
        String imsi = ImsiMask.mask(row.getString("imsi"), row.getString("plmn"));
        if (imsi != null) data.put("imsi", imsi);
    }
}
