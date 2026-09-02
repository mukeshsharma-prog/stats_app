package com.zenner.datahub.stats.writer.retention;

import com.zenner.datahub.stats.common.model.StatType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** Creates the daily PostgreSQL partitions required before Writer inserts arrive. */
@Component
public class PartitionPrecreator {

    private static final String TRY_LOCK_SQL =
            "SELECT pg_try_advisory_lock(hashtext('stats.retention:' || ?))";
    private static final String UNLOCK_SQL =
            "SELECT pg_advisory_unlock(hashtext('stats.retention:' || ?))";
    private static final DateTimeFormatter PARTITION_DATE =
            DateTimeFormatter.ofPattern("uuuu_MM_dd");

    private final DataSource dataSource;

    public PartitionPrecreator(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Returns known stat types whose partitioned parent table currently exists.
     * This lets the development {@code all} deployment grow automatically as each
     * stat-type migration lands, without special-casing existing types.
     */
    public List<StatType> existingParentTypes() {
        List<StatType> existing = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT COALESCE((SELECT relkind = 'p' FROM pg_class "
                                        + "WHERE oid = to_regclass(?)), false)")) {
            for (StatType type : StatType.values()) {
                statement.setString(1, type.wireName());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next() && result.getBoolean(1)) {
                        existing.add(type);
                    }
                }
            }
            return List.copyOf(existing);
        } catch (SQLException exception) {
            throw maintenanceFailure("discover partitioned parent tables", exception);
        }
    }

    /**
     * Ensures the UTC partition for {@code firstDay} and every day through
     * {@code firstDay + preCreateDays}. The current-day partition is included so
     * a fresh deployment can ingest immediately; seven days ahead therefore means
     * eight ensured partitions.
     *
     * <p>The session-level advisory lock and every DDL statement deliberately use
     * the same physical connection. The lock is explicitly released before that
     * pooled connection is returned, including when partition creation fails.
     */
    public PartitionPrecreationResult ensurePartitions(
            StatType type, LocalDate firstDay, int preCreateDays) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(firstDay, "firstDay");
        if (preCreateDays < 0) {
            throw new IllegalArgumentException("preCreateDays must be zero or greater");
        }
        if (preCreateDays > RetentionProperties.MAX_PRE_CREATE_DAYS) {
            throw new IllegalArgumentException(
                    "preCreateDays must not exceed " + RetentionProperties.MAX_PRE_CREATE_DAYS);
        }
        // Validate the exclusive upper boundary before acquiring a lock or issuing
        // any DDL. This also accounts for the day after the final partition.
        try {
            firstDay.plusDays((long) preCreateDays + 1L);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException(
                    "The requested partition horizon exceeds LocalDate limits", exception);
        }

        try (Connection connection = dataSource.getConnection()) {
            boolean lockAcquired = tryLock(connection, type);
            if (!lockAcquired) {
                return PartitionPrecreationResult.lockNotAcquired();
            }

            try {
                setUtcTimezone(connection);
                List<LocalDate> days = new ArrayList<>(preCreateDays + 1);
                for (int offset = 0; offset <= preCreateDays; offset++) {
                    days.add(firstDay.plusDays(offset));
                }

                // Validate every pre-existing child before creating anything. A
                // same-named child with a different range must never be accepted.
                List<Boolean> existing = new ArrayList<>(days.size());
                for (LocalDate day : days) {
                    existing.add(validateExistingPartition(connection, type, day));
                }

                int ensured = 0;
                for (int index = 0; index < days.size(); index++) {
                    if (!existing.get(index)) {
                        createPartition(connection, type, days.get(index));
                    }
                    ensured++;
                }
                return PartitionPrecreationResult.completed(ensured);
            } finally {
                unlock(connection, type);
            }
        } catch (SQLException exception) {
            throw maintenanceFailure("pre-create partitions for " + type.wireName(), exception);
        }
    }

    private static boolean tryLock(Connection connection, StatType type) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TRY_LOCK_SQL)) {
            statement.setString(1, type.wireName());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection connection, StatType type) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UNLOCK_SQL)) {
            statement.setString(1, type.wireName());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new SQLException(
                            "PostgreSQL advisory lock was not held for " + type.wireName());
                }
            }
        }
    }

    private static void setUtcTimezone(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET TIME ZONE 'UTC'");
        }
    }

    private static boolean validateExistingPartition(
            Connection connection, StatType type, LocalDate day)
            throws SQLException {
        String partitionName = type.wireName() + "_" + PARTITION_DATE.format(day);
        boolean relationExists = false;
        boolean isChildOfExpectedParent = false;
        String actualBound = null;
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT pg_get_expr(child.relpartbound, child.oid), "
                                + "(inheritance.inhparent = to_regclass(?)) "
                                + "FROM pg_class child "
                                + "LEFT JOIN pg_inherits inheritance ON inheritance.inhrelid = child.oid "
                                + "WHERE child.relname = ?")) {
            statement.setString(1, type.wireName());
            statement.setString(2, partitionName);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    relationExists = true;
                    actualBound = result.getString(1);
                    isChildOfExpectedParent = result.getBoolean(2);
                }
            }
        }

        if (relationExists) {
            if (!isChildOfExpectedParent || actualBound == null) {
                throw new IllegalStateException(
                        "Existing relation "
                                + partitionName
                                + " is not a partition of "
                                + type.wireName());
            }
            String expectedBound = partitionBound(day);
            if (!expectedBound.equals(actualBound)) {
                throw new IllegalStateException(
                        "Existing partition "
                                + partitionName
                                + " has incorrect bounds: expected "
                                + expectedBound
                                + " but found "
                                + actualBound);
            }
            return true;
        }
        return false;
    }

    private static void createPartition(Connection connection, StatType type, LocalDate day)
            throws SQLException {
        String parent = quotedIdentifier(type.wireName());
        String child = quotedIdentifier(type.wireName() + "_" + PARTITION_DATE.format(day));
        String bound = partitionBound(day);
        String sql =
                "CREATE TABLE "
                        + child
                        + " PARTITION OF "
                        + parent
                        + " "
                        + bound;
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String partitionBound(LocalDate day) {
        return "FOR VALUES FROM ('"
                + day
                + " 00:00:00+00') TO ('"
                + day.plusDays(1)
                + " 00:00:00+00')";
    }

    private static String quotedIdentifier(String identifier) {
        // Identifiers originate only from the closed StatType enum and a LocalDate.
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static IllegalStateException maintenanceFailure(String action, SQLException cause) {
        return new IllegalStateException("Failed to " + action, cause);
    }

    public record PartitionPrecreationResult(boolean lockAcquired, int partitionsEnsured) {

        public PartitionPrecreationResult {
            if (partitionsEnsured < 0 || (!lockAcquired && partitionsEnsured != 0)) {
                throw new IllegalArgumentException("Invalid partition pre-creation result");
            }
        }

        static PartitionPrecreationResult lockNotAcquired() {
            return new PartitionPrecreationResult(false, 0);
        }

        static PartitionPrecreationResult completed(int partitionsEnsured) {
            return new PartitionPrecreationResult(true, partitionsEnsured);
        }
    }
}
