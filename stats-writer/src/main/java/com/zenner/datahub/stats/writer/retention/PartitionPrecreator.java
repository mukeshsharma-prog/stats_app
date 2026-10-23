package com.zenner.datahub.stats.writer.retention;

import com.zenner.datahub.stats.common.model.StatType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** Creates the daily PostgreSQL partitions required before Writer inserts arrive. */
@Component
public class PartitionPrecreator {

    private static final String PARENT_EXISTS_SQL =
            "SELECT COALESCE((SELECT relkind = 'p' FROM pg_class "
                    + "WHERE oid = to_regclass(?)), false)";

    private final DataSource dataSource;
    private final PartitionSession session;

    public PartitionPrecreator(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.session = new PartitionSession(dataSource);
    }

    /**
     * Returns known stat types whose partitioned parent table currently exists.
     * This lets the development {@code all} deployment grow automatically as each
     * stat-type migration lands, without special-casing existing types.
     */
    public List<StatType> existingParentTypes() {
        List<StatType> existing = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(PARENT_EXISTS_SQL)) {
            for (StatType type : StatType.values()) {
                if (parentTableExists(statement, type)) {
                    existing.add(type);
                }
            }
            return List.copyOf(existing);
        } catch (SQLException exception) {
            throw maintenanceFailure("discover partitioned parent tables", exception);
        }
    }

    /** Whether {@code type}'s partitioned parent table has been migrated into existence. */
    public boolean parentTableExists(StatType type) {
        Objects.requireNonNull(type, "type");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(PARENT_EXISTS_SQL)) {
            return parentTableExists(statement, type);
        } catch (SQLException exception) {
            throw maintenanceFailure("check the parent table for " + type.wireName(), exception);
        }
    }

    private static boolean parentTableExists(PreparedStatement statement, StatType type)
            throws SQLException {
        statement.setString(1, type.wireName());
        try (ResultSet result = statement.executeQuery()) {
            return result.next() && result.getBoolean(1);
        }
    }

    /**
     * Ensures the UTC partition for {@code firstDay} and every day through
     * {@code firstDay + daysAfterFirst}, inclusive at both ends — so {@code 7} means
     * eight ensured partitions.
     *
     * <p>{@link PartitionMaintenance} calls this with the retention cutoff day as
     * {@code firstDay} and the whole window plus the pre-creation horizon as the span.
     * That is what makes the {@link RetentionWindow} contract true: every day a
     * record can be admitted for has a partition, whether the deployment is a day old or
     * a year old, and a day the schedule missed during an outage is filled by the next
     * pass rather than staying a hole that dead-letters valid records.</p>
     *
     * <p>The session-level advisory lock and every DDL statement deliberately use
     * the same physical connection, and the lock is released before that pooled
     * connection is returned even when partition creation fails —
     * {@link PartitionSession} owns both guarantees, and retention holds the same lock
     * through the same class so the two jobs cannot run concurrently on one type.
     */
    public PartitionPrecreationResult ensurePartitions(
            StatType type, LocalDate firstDay, int daysAfterFirst) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(firstDay, "firstDay");
        if (daysAfterFirst < 0) {
            throw new IllegalArgumentException("daysAfterFirst must be zero or greater");
        }
        if (daysAfterFirst > RetentionProperties.MAX_ENSURED_DAYS) {
            throw new IllegalArgumentException(
                    "daysAfterFirst must not exceed " + RetentionProperties.MAX_ENSURED_DAYS);
        }
        // Validate the exclusive upper boundary before acquiring a lock or issuing
        // any DDL. This also accounts for the day after the final partition.
        try {
            firstDay.plusDays((long) daysAfterFirst + 1L);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException(
                    "The requested partition horizon exceeds LocalDate limits", exception);
        }

        return session.withLock(
                        type,
                        "pre-create partitions for " + type.wireName(),
                        connection -> {
                            List<LocalDate> days = new ArrayList<>(daysAfterFirst + 1);
                            for (int offset = 0; offset <= daysAfterFirst; offset++) {
                                days.add(firstDay.plusDays(offset));
                            }

                            // Validate every pre-existing child before creating anything.
                            // A same-named child with a different range must never be
                            // accepted.
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
                        })
                .orElseGet(PartitionPrecreationResult::lockNotAcquired);
    }

    private static boolean validateExistingPartition(
            Connection connection, StatType type, LocalDate day)
            throws SQLException {
        String partitionName = PartitionNaming.partitionName(type, day);
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
            String expectedBound = PartitionNaming.partitionBound(day);
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
        String parent = PartitionNaming.quotedIdentifier(type.wireName());
        String child = PartitionNaming.quotedIdentifier(PartitionNaming.partitionName(type, day));
        String sql =
                "CREATE TABLE "
                        + child
                        + " PARTITION OF "
                        + parent
                        + " "
                        + PartitionNaming.partitionBound(day);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
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
