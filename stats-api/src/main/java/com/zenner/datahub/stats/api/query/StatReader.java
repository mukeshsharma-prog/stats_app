package com.zenner.datahub.stats.api.query;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.common.model.StatType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * How one stat type's table is read back into the contract's {@code data} shape.
 *
 * <p><strong>One reader per table, and a new type is a new reader</strong>, so adding a
 * type never edits an existing one. Nothing here is shared between types except the envelope, which
 * {@link StatsQueryBuilder} and {@link RecordAssembler} own. A type with no reader has no
 * table yet; {@code StatReadersCoverEveryTableTest} fails the build when a
 * migration creates a table that no reader serves, so a new type cannot silently answer
 * every read with an empty page.</p>
 *
 * <p><strong>An unset column is omitted, never {@code null}</strong> (openapi.yaml,
 * {@code LorastatsPage}). Values go back in the contract's representation for the type —
 * which is not always the column's: {@code sysstats.timespan} is stored as {@code bigint}
 * and returned as the string it arrived as.</p>
 */
public interface StatReader {

    StatType type();

    /** The typed payload columns to select, in the order they are written into {@code data}. */
    List<String> dataColumns();

    /** Writes this row's typed payload members into {@code data}, skipping NULL columns. */
    void putData(ResultSet row, ObjectNode data) throws SQLException;

    /** The table: the wire name, by the rule {@link StatType} fixes for every identity. */
    default String table() {
        return type().wireName();
    }

    static void putLong(ResultSet row, String column, ObjectNode data) throws SQLException {
        long value = row.getLong(column);
        if (!row.wasNull()) {
            data.put(column, value);
        }
    }

    static void putDouble(ResultSet row, String column, ObjectNode data) throws SQLException {
        double value = row.getDouble(column);
        if (!row.wasNull()) {
            data.put(column, value);
        }
    }

    static void putText(ResultSet row, String column, ObjectNode data) throws SQLException {
        String value = row.getString(column);
        if (value != null) {
            data.put(column, value);
        }
    }

    /** A {@code bigint} column whose wire representation is a decimal string. */
    static void putLongAsString(ResultSet row, String column, ObjectNode data) throws SQLException {
        long value = row.getLong(column);
        if (!row.wasNull()) {
            data.put(column, Long.toString(value));
        }
    }
}
