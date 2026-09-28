package com.zenner.datahub.stats.api.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * Rebuilds a stored row into the record the contract returns — the production envelope,
 * with {@code data} in the type's canonical shape (openapi.yaml {@code StatRecord}).
 *
 * <p>Three sources, one record. The envelope's typed columns; {@code passthrough}, which
 * holds the envelope members the system stores and never reads ({@code ts.datastore},
 * {@code ts.converter}, {@code ts.expiresOn} in whichever spelling arrived,
 * {@code ts.deviceOrig}, {@code status}, {@code meta}); and the payload, which is the
 * type's typed columns plus {@code data_extra} — the unknown {@code data} members the
 * Writer preserved verbatim (D14, DL-095). A typed column always wins over a same-named
 * member of {@code data_extra}, which the Writer never produces.</p>
 *
 * <p>Timestamps are Unix <strong>seconds</strong>, on the way out as on the way in.</p>
 */
public final class RecordAssembler {

    private final ObjectMapper objectMapper;

    public RecordAssembler(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** The row's record, and its keyset position for the next page. */
    public record Row(ObjectNode record, StatsQueryBuilder.Keyset position) {}

    public Row assemble(StatReader reader, ResultSet row) throws SQLException {
        String uid = row.getString("uid");
        long tsDevice = row.getObject("ts_device", OffsetDateTime.class).toEpochSecond();
        long tsServer = row.getObject("ts_server", OffsetDateTime.class).toEpochSecond();
        ObjectNode passthrough = json(row.getString("passthrough"));

        ObjectNode record = objectMapper.createObjectNode();
        record.put("uid", uid);
        record.put("tenant", row.getString("tenant"));
        record.put("maintenant", row.getString("maintenant"));
        record.put("clsbox", row.getString("clsbox"));
        record.put("type", reader.type().wireName());
        int version = row.getInt("record_version");
        if (!row.wasNull()) {
            record.put("version", version);
        }

        ObjectNode ts = record.putObject("ts");
        ts.put("server", tsServer);
        ts.put("device", tsDevice);
        if (passthrough != null && passthrough.get("ts") instanceof ObjectNode stored) {
            copyAbsent(stored, ts);
        }
        if (passthrough != null) {
            copyIfPresent(passthrough, "status", record);
            copyIfPresent(passthrough, "meta", record);
        }

        ObjectNode data = record.putObject("data");
        reader.putData(row, data);
        ObjectNode extra = json(row.getString("data_extra"));
        if (extra != null) {
            copyAbsent(extra, data);
        }
        return new Row(record, new StatsQueryBuilder.Keyset(tsDevice, uid));
    }

    private static void copyAbsent(ObjectNode from, ObjectNode to) {
        for (Map.Entry<String, JsonNode> member : from.properties()) {
            if (!to.has(member.getKey())) {
                to.set(member.getKey(), member.getValue());
            }
        }
    }

    private static void copyIfPresent(ObjectNode from, String name, ObjectNode to) {
        JsonNode value = from.get(name);
        if (value != null && !value.isNull()) {
            to.set(name, value);
        }
    }

    private ObjectNode json(String text) throws SQLException {
        if (text == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            return node instanceof ObjectNode object ? object : null;
        } catch (JsonProcessingException unreadable) {
            // The column is jsonb, so PostgreSQL already parsed it once; this cannot fail
            // for a value the database accepted. Surface it as a database failure rather
            // than return a record with members silently missing — and without the
            // parser's message, which quotes the stored value.
            throw new SQLException("Stored JSON column could not be read", "XX000");
        }
    }
}
