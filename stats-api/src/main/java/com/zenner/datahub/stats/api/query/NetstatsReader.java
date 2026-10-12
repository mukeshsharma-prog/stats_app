package com.zenner.datahub.stats.api.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.common.model.StatType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds {@code data.eth0}, {@code data.wwan} and integer {@code data.timespan}.
 *
 * <p>The 9 October 2026 live export has {@code gw}, {@code tx}, {@code rx},
 * {@code ip}, {@code dns} and {@code mask} on eth0; wwan also has {@code driver}
 * and {@code mtu}. Addresses/driver are strings, DNS is an array of strings,
 * and tx/rx/mtu are decimal strings. The database stores both interfaces inside
 * the {@code interfaces} JSONB column; that storage key is never returned in data.
 * Other accepted interfaces and optional members remain intact.
 */
public final class NetstatsReader implements StatReader {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> COLUMNS = List.of("timespan", "interfaces");
    private static final List<String> COUNTERS =
            List.of("tx", "rx", "mtu", "fail_count", "last_forced_reset", "last_powercycle");

    @Override
    public StatType type() {
        return StatType.NETSTATS;
    }

    @Override
    public List<String> dataColumns() {
        return COLUMNS;
    }

    @Override
    public void putData(ResultSet row, ObjectNode data) throws SQLException {
        StatReader.putLong(row, "timespan", data);
        final JsonNode interfaces;
        try {
            String stored = row.getString("interfaces");
            interfaces = stored == null ? null : JSON.readTree(stored);
        } catch (JsonProcessingException failure) {
            throw new SQLException("Stored interface map could not be read", "XX000");
        }
        if (!(interfaces instanceof ObjectNode map)) {
            throw new SQLException("Stored interface map must be an object", "XX000");
        }
        for (Map.Entry<String, JsonNode> member : map.properties()) {
            if (!(member.getValue() instanceof ObjectNode object)) {
                throw new SQLException("Stored interface must be an object", "XX000");
            }
            ObjectNode restored = object.deepCopy();
            for (String field : COUNTERS) {
                JsonNode counter = restored.get(field);
                if (counter == null) continue;
                if (!counter.isIntegralNumber() || !counter.canConvertToLong() || counter.longValue() < 0) {
                    throw new SQLException("Stored interface counter must be a nonnegative BIGINT", "XX000");
                }
                restored.put(field, counter.asText());
            }
            data.set(member.getKey(), restored);
        }
    }
}
