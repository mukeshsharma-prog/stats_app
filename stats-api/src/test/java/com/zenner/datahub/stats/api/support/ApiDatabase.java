package com.zenner.datahub.stats.api.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The database the API tests read from, built from production artifacts only (DL-162).
 *
 * <ul>
 *   <li>PostgreSQL 17 — the image {@code stats-testsupport}'s {@code PostgresSupport}
 *       uses, which this module cannot depend on (NoBrokerConnectionTest).</li>
 *   <li>{@code docker/postgres-init/10-read-only-user.sql} — the shipped role bootstrap,
 *       so {@code stats_ro} has exactly its production grants (DL-135).</li>
 *   <li>The Writer's Flyway migrations, applied as the owner — so every column the API
 *       selects is one the Writer creates, and a migration that renames one breaks these
 *       tests rather than production.</li>
 * </ul>
 *
 * <p>The API connects as {@code stats_ro}; rows are inserted here as the owner, as the
 * Writer would.</p>
 */
public final class ApiDatabase {

    public static final String IMAGE = "postgres:17-alpine";
    public static final String DATABASE = "statsdb";
    public static final String OWNER = "stats";
    public static final String OWNER_PASSWORD = "writer-test-password";
    public static final String READ_ONLY = "stats_ro";
    public static final String READ_ONLY_PASSWORD = "reader-test-password";

    private static final Set<String> JSONB = Set.of("passthrough", "data_extra");
    private static final DateTimeFormatter PARTITION_DAY = DateTimeFormatter.ofPattern("uuuu_MM_dd");

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(IMAGE)
                    .withDatabaseName(DATABASE)
                    .withUsername(OWNER)
                    .withPassword(OWNER_PASSWORD)
                    .withEnv("POSTGRES_RO_USER", READ_ONLY)
                    .withEnv("POSTGRES_RO_PASSWORD", READ_ONLY_PASSWORD)
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("10-read-only-user.sql"),
                            "/docker-entrypoint-initdb.d/10-read-only-user.sql");

    static {
        POSTGRES.start();
        String migrations = System.getProperty("stats.test.writer-migrations");
        if (migrations == null) {
            throw new IllegalStateException("stats.test.writer-migrations is set by the Gradle test task");
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), OWNER, OWNER_PASSWORD)
                .locations("filesystem:" + migrations)
                .load()
                .migrate();
    }

    private ApiDatabase() {}

    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    public static Connection owner() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), OWNER, OWNER_PASSWORD);
    }

    /** The daily partition the Writer's precreator would have made (DL-149). */
    public static void ensurePartition(String table, LocalDate day) {
        String name = table + "_" + day.format(PARTITION_DAY);
        execute(
                "CREATE TABLE IF NOT EXISTS " + name + " PARTITION OF " + table
                        + " FOR VALUES FROM ('" + day + " 00:00:00+00') TO ('" + day.plusDays(1)
                        + " 00:00:00+00')");
    }

    /** Inserts one row as the owner. {@code ts_server}/{@code ts_device} are epoch seconds. */
    public static void insert(String table, Map<String, Object> row) {
        insertAll(table, List.of(row));
    }

    public static void insertAll(String table, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        try (Connection connection = owner()) {
            for (Map<String, Object> row : rows) {
                ensurePartition(
                        table,
                        LocalDate.ofInstant(
                                Instant.ofEpochSecond((Long) row.get("ts_device")), ZoneOffset.UTC));
                List<String> columns = new ArrayList<>(row.keySet());
                List<String> placeholders = new ArrayList<>();
                for (String column : columns) {
                    placeholders.add(JSONB.contains(column) ? "CAST(? AS jsonb)" : "?");
                }
                String sql = "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                        + String.join(", ", placeholders) + ")";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (int i = 0; i < columns.size(); i++) {
                        Object value = row.get(columns.get(i));
                        if (columns.get(i).startsWith("ts_") && value instanceof Long seconds) {
                            value = OffsetDateTime.ofInstant(Instant.ofEpochSecond(seconds), ZoneOffset.UTC);
                        }
                        statement.setObject(i + 1, value);
                    }
                    statement.executeUpdate();
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static long count(String table) {
        try (Connection connection = owner();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** The partitioned parents — one per stat type the Writer has a table for. */
    public static Set<String> partitionedTables() {
        try (Connection connection = owner();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT relname FROM pg_class WHERE relkind = 'p' "
                                        + "AND relnamespace = 'public'::regnamespace")) {
            Set<String> tables = new TreeSet<>();
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
            return tables;
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** The minimal NOT NULL envelope of a row; callers add the payload columns. */
    public static Map<String, Object> envelope(
            String uid, String tenant, String maintenant, String clsbox, long tsDevice) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("uid", uid);
        row.put("tenant", tenant);
        row.put("maintenant", maintenant);
        row.put("clsbox", clsbox);
        row.put("ts_server", tsDevice);
        row.put("ts_device", tsDevice);
        row.put("schema_version", (short) 1);
        return row;
    }

    /** A valid lorastats row: the thirteen required counters, all zero unless set. */
    public static Map<String, Object> lorastats(
            String uid, String tenant, String maintenant, String clsbox, long tsDevice) {
        Map<String, Object> row = envelope(uid, tenant, maintenant, clsbox, tsDevice);
        for (String counter :
                List.of(
                        "rx_nr_pkts", "rx_crc_missing", "rx_crc_bad", "tx_success", "tx_failed",
                        "tx_peek", "tx_peek_index_zero", "tx_override", "tx_busy", "tx_dequeue_error",
                        "tx_type_beacon", "chip_comm_error", "util_fopen_failed")) {
            row.put(counter, 0L);
        }
        return row;
    }

    private static void execute(String sql) {
        try (Connection connection = owner();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
