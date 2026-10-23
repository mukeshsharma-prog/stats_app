package com.zenner.datahub.stats.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Proves the production database-role bootstrap against PostgreSQL 17. */
class DatabaseRolesIntegrationTest {

    private static final String DATABASE = "statsdb";
    private static final String OWNER = "stats";
    private static final String OWNER_PASSWORD = "writer-test-password";
    private static final String READ_ONLY = "stats_ro";
    private static final String READ_ONLY_PASSWORD = "reader-test-password";
    private static final String TABLE = "roles_role_probe";
    private static final String SEQUENCE = "roles_sequence_probe";

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(PostgresSupport.IMAGE)
                    .withDatabaseName(DATABASE)
                    .withUsername(OWNER)
                    .withPassword(OWNER_PASSWORD)
                    .withEnv("POSTGRES_RO_USER", READ_ONLY)
                    .withEnv("POSTGRES_RO_PASSWORD", READ_ONLY_PASSWORD)
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("10-read-only-user.sql"),
                            "/docker-entrypoint-initdb.d/10-read-only-user.sql");

    @BeforeAll
    static void createAFutureFlywayOwnedTable() throws Exception {
        POSTGRES.start();
        try (Connection connection = connect(OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement()) {
            // Created after the init script: SELECT for stats_ro must therefore come
            // from ALTER DEFAULT PRIVILEGES, not the one-time existing-table sweep.
            statement.execute(
                    "CREATE TABLE " + TABLE + " (id bigint PRIMARY KEY, value text NOT NULL)");
            statement.execute("CREATE SEQUENCE " + SEQUENCE);
            statement.executeUpdate(
                    "INSERT INTO " + TABLE + " (id, value) VALUES (1, 'visible-to-reader')");
        }
    }

    @AfterAll
    static void dropProbeTable() throws Exception {
        try {
            if (POSTGRES.isRunning()) {
                try (Connection connection = connect(OWNER, OWNER_PASSWORD);
                    Statement statement = connection.createStatement()) {
                    statement.execute("DROP TABLE IF EXISTS " + TABLE);
                    statement.execute("DROP SEQUENCE IF EXISTS " + SEQUENCE);
                }
            }
        } finally {
            POSTGRES.stop();
        }
    }

    @Test
    void writerOwnerCanCreateDropSelectAndInsert() throws Exception {
        try (Connection connection = connect(OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement()) {
            assertThat(statement.executeUpdate("INSERT INTO " + TABLE + " VALUES (2, 'writer')"))
                    .isOne();
            try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + TABLE)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }

            statement.execute("CREATE TABLE roles_drop_probe (id bigint)");
            statement.execute("DROP TABLE roles_drop_probe");
        }
    }

    @Test
    void readOnlyRoleCanSelectFromATableCreatedAfterBootstrap() throws Exception {
        try (Connection connection = connect(READ_ONLY, READ_ONLY_PASSWORD);
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery("SELECT value FROM " + TABLE + " WHERE id = 1")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("visible-to-reader");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "INSERT INTO roles_role_probe VALUES (99, 'forbidden')",
                "UPDATE roles_role_probe SET value = 'forbidden' WHERE id = 1",
                "DELETE FROM roles_role_probe WHERE id = 1",
                "TRUNCATE TABLE roles_role_probe",
                "CREATE TABLE roles_reader_must_not_create (id bigint)",
                "CREATE TEMP TABLE roles_reader_temp (id bigint)",
                "SELECT nextval('roles_sequence_probe')"
            })
    void everyWriteAttemptAsStatsRoIsADatabasePermissionError(String sql) {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(
                        () -> {
                            try (Connection connection = connect(READ_ONLY, READ_ONLY_PASSWORD);
                                    Statement statement = connection.createStatement()) {
                                statement.execute(sql);
                            }
                        })
                .satisfies(failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
    }

    @Test
    void readOnlyRoleCannotInheritWriterAndInputHasNoDatabaseIdentity() throws Exception {
        try (Connection connection = connect(OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "SELECT "
                                        + "pg_has_role('stats_ro', 'stats', 'MEMBER') AS inherits_writer, "
                                        + "EXISTS (SELECT FROM pg_roles WHERE rolname = 'stats_input') "
                                        + "AS input_role_exists")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getBoolean("inherits_writer")).isFalse();
            assertThat(result.getBoolean("input_role_exists")).isFalse();
        }
    }

    private static Connection connect(String username, String password) throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), username, password);
    }
}
