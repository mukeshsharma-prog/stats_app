package com.zenner.datahub.stats.writer.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.testsupport.PostgresSupport;
import com.zenner.datahub.stats.writer.StatsWriterApplication;
import com.zenner.datahub.stats.writer.retention.PartitionPrecreator.PartitionPrecreationResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** T-012 proof against the real PostgreSQL 17 partition and advisory-lock behavior. */
@SpringBootTest(
        classes = StatsWriterApplication.class,
        properties = {
            "stats.writer.stat-type=netstats",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "stats.retention.schedule=-",
            // These tests create and drop their own partitions on fixed far-future
            // days. The startup pre-creation is exercised by
            // PartitionBootstrapIntegrationTest, which leaves it on.
            "stats.retention.pre-create-on-startup=false"
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresSupport.class)
class PartitionPrecreatorIntegrationTest {

    private static final DateTimeFormatter PARTITION_DATE =
            DateTimeFormatter.ofPattern("uuuu_MM_dd");
    private static final LocalDate IDEMPOTENCE_START = LocalDate.of(2098, 3, 1);
    private static final LocalDate LOCKED_START = LocalDate.of(2098, 4, 1);
    private static final LocalDate MISSING_START = LocalDate.of(2098, 5, 1);
    private static final LocalDate YEAR_BOUNDARY_START = LocalDate.of(2098, 12, 29);
    private static final LocalDate WRONG_BOUNDS_START = LocalDate.of(2098, 6, 1);
    private static final LocalDate SESSION_ZONE_START = LocalDate.of(2098, 7, 1);

    @Autowired
    private PartitionPrecreator precreator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    @AfterEach
    void removeTestPartitions() {
        for (LocalDate start :
                List.of(
                        IDEMPOTENCE_START,
                        LOCKED_START,
                        MISSING_START,
                        YEAR_BOUNDARY_START,
                        WRONG_BOUNDS_START,
                        SESSION_ZONE_START)) {
            for (int offset = 0; offset <= 7; offset++) {
                jdbcTemplate.execute(
                        "DROP TABLE IF EXISTS " + partitionName(start.plusDays(offset)));
            }
        }
    }

    @Test
    void createsTodayAndSevenFutureUtcPartitionsIdempotentlyWithInheritedIndexes() {
        assertThat(precreator.existingParentTypes()).contains(StatType.LORASTATS);

        PartitionPrecreationResult first =
                precreator.ensurePartitions(StatType.LORASTATS, IDEMPOTENCE_START, 7);
        PartitionPrecreationResult second =
                precreator.ensurePartitions(StatType.LORASTATS, IDEMPOTENCE_START, 7);

        assertThat(first).isEqualTo(new PartitionPrecreationResult(true, 8));
        assertThat(second).isEqualTo(new PartitionPrecreationResult(true, 8));
        assertThat(childPartitions(IDEMPOTENCE_START, 8))
                .containsExactlyElementsOf(expectedPartitionNames(IDEMPOTENCE_START, 8));

        String firstPartition = partitionName(IDEMPOTENCE_START);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT pg_get_expr(relpartbound, oid) FROM pg_class WHERE relname = ?",
                                String.class,
                                firstPartition))
                // PostgreSQL renders timestamptz catalog values in the session zone;
                // the dates still prove adjacent, non-overlapping one-day bounds.
                .contains("FOR VALUES FROM")
                .contains("2098-03-01")
                .contains("2098-03-02");
        // A new partition inherits the parent's whole ADR-003 index direction: the
        // primary key plus both indexes V3 creates. Asserting the shape rather than a
        // bare count means a partition created without the tenant-leading indexes --
        // which would silently scan in production -- fails here.
        assertThat(
                        jdbcTemplate.queryForList(
                                "SELECT indexdef FROM pg_indexes WHERE tablename = ?",
                                String.class,
                                firstPartition))
                .hasSize(3)
                .anySatisfy(index -> assertThat(index).contains("(ts_device, uid)"))
                .anySatisfy(
                        index ->
                                assertThat(index)
                                        .contains("(tenant, clsbox, ts_device DESC, uid DESC)"))
                .anySatisfy(index -> assertThat(index).contains("(tenant, uid)"));
    }

    @Test
    void handlesMonthAndYearBoundariesWithoutManualCalendarLogic() {
        PartitionPrecreationResult result =
                precreator.ensurePartitions(StatType.LORASTATS, YEAR_BOUNDARY_START, 7);

        assertThat(result).isEqualTo(new PartitionPrecreationResult(true, 8));
        assertThat(childPartitions(YEAR_BOUNDARY_START, 8))
                .containsExactlyElementsOf(expectedPartitionNames(YEAR_BOUNDARY_START, 8));
        assertThat(expectedPartitionNames(YEAR_BOUNDARY_START, 8))
                .contains("lorastats_2098_12_31", "lorastats_2099_01_01", "lorastats_2099_01_05");
    }

    @Test
    void rejectsAnExistingPartitionWithTheExpectedNameButIncorrectBoundsBeforeAnyDdl() {
        String wrongPartition = partitionName(WRONG_BOUNDS_START);
        jdbcTemplate.execute(
                "CREATE TABLE "
                        + wrongPartition
                        + " PARTITION OF lorastats FOR VALUES FROM ('2098-06-02 00:00:00+00') "
                        + "TO ('2098-06-03 00:00:00+00')");

        assertThatThrownBy(
                        () ->
                                precreator.ensurePartitions(
                                        StatType.LORASTATS, WRONG_BOUNDS_START, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has incorrect bounds")
                .hasMessageContaining(wrongPartition);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT to_regclass(?)",
                                String.class,
                                partitionName(WRONG_BOUNDS_START.plusDays(1))))
                .isNull();
    }

    @Test
    void rejectsAnExcessiveHorizonBeforeOpeningAConnection() {
        assertThatThrownBy(
                        () ->
                                precreator.ensurePartitions(
                                        StatType.LORASTATS,
                                        LocalDate.of(2098, 7, 1),
                                        RetentionProperties.MAX_ENSURED_DAYS + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed");
    }

    @Test
    void rejectsDateOverflowBeforeCreatingAnyPartition() {
        assertThatThrownBy(
                        () -> precreator.ensurePartitions(StatType.LORASTATS, LocalDate.MAX, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds LocalDate limits");
    }

    @Test
    void competingReplicaSkipsWithoutCreatingAnything() throws Exception {
        try (Connection lockHolder = dataSource.getConnection()) {
            assertThat(changeLock(lockHolder, "pg_try_advisory_lock", StatType.LORASTATS))
                    .isTrue();
            try {
                PartitionPrecreationResult result =
                        precreator.ensurePartitions(StatType.LORASTATS, LOCKED_START, 0);

                assertThat(result).isEqualTo(new PartitionPrecreationResult(false, 0));
                assertThat(
                                jdbcTemplate.queryForObject(
                                        "SELECT to_regclass(?)", String.class, partitionName(LOCKED_START)))
                        .isNull();
            } finally {
                assertThat(changeLock(lockHolder, "pg_advisory_unlock", StatType.LORASTATS))
                        .isTrue();
            }
        }
    }

    @Test
    void missingPartitionFailsLoudlyUntilPrecreationRepairsTheDay() {
        OffsetDateTime deviceTime = MISSING_START.atTime(12, 0).atOffset(ZoneOffset.UTC);

        assertThatThrownBy(() -> insertMinimumRow("missing-partition-before", deviceTime))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(
                        failure -> {
                            Throwable cause =
                                    ((DataIntegrityViolationException) failure)
                                            .getMostSpecificCause();
                            assertThat(cause).isInstanceOf(SQLException.class);
                            assertThat(((SQLException) cause).getSQLState()).isEqualTo("23514");
                            assertThat(cause.getMessage())
                                    .contains("no partition of relation \"lorastats\" found");
                        });

        precreator.ensurePartitions(StatType.LORASTATS, MISSING_START, 0);
        assertThat(insertMinimumRow("missing-partition-after", deviceTime)).isEqualTo(1);
    }

    @Test
    void conflictingRelationIsLoudAndDoesNotLeakTheSessionAdvisoryLock() throws Exception {
        String conflict = "ltestats_" + MISSING_START.format(PARTITION_DATE);
        jdbcTemplate.execute("CREATE TABLE " + conflict + " (id integer)");
        try {
            assertThatThrownBy(() -> precreator.ensurePartitions(StatType.LTESTATS, MISSING_START, 0))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not a partition of ltestats");
            try (Connection verifier = dataSource.getConnection()) {
                assertThat(changeLock(verifier, "pg_try_advisory_lock", StatType.LTESTATS)).isTrue();
                assertThat(changeLock(verifier, "pg_advisory_unlock", StatType.LTESTATS)).isTrue();
            }
        } finally {
            jdbcTemplate.execute("DROP TABLE " + conflict);
        }
    }

    private List<String> childPartitions(LocalDate start, int count) {
        List<String> expected = expectedPartitionNames(start, count);
        return jdbcTemplate
                .queryForList(
                        """
                        SELECT child.relname
                        FROM pg_inherits inheritance
                        JOIN pg_class parent ON parent.oid = inheritance.inhparent
                        JOIN pg_class child ON child.oid = inheritance.inhrelid
                        WHERE parent.relname = 'lorastats'
                        ORDER BY child.relname
                        """,
                        String.class)
                .stream()
                .filter(expected::contains)
                .toList();
    }

    private static List<String> expectedPartitionNames(LocalDate start, int count) {
        List<String> names = new ArrayList<>();
        for (int offset = 0; offset < count; offset++) {
            names.add(partitionName(start.plusDays(offset)));
        }
        return names;
    }

    private int insertMinimumRow(String uid, OffsetDateTime deviceTime) {
        return jdbcTemplate.update(
                """
                INSERT INTO lorastats (
                    uid, tenant, maintenant, clsbox, ts_server, ts_device,
                    schema_version, rx_nr_pkts, rx_crc_missing, rx_crc_bad,
                    tx_success, tx_failed, tx_peek, tx_peek_index_zero,
                    tx_override, tx_busy, tx_dequeue_error, tx_type_beacon,
                    chip_comm_error, util_fopen_failed
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                uid,
                "tenant-t012",
                "maintenant-t012",
                "A1B2C3D4E5F6",
                deviceTime,
                deviceTime,
                (short) 1,
                10L,
                0L,
                0L,
                5L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L);
    }

    private static boolean changeLock(Connection connection, String function, StatType type)
            throws Exception {
        String sql = "SELECT " + function + "(hashtext('stats.retention:' || ?))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, type.wireName());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static String partitionName(LocalDate date) {
        return "lorastats_" + PARTITION_DATE.format(date);
    }

    /**
     * The session time zone is set to UTC for the bound comparison — {@code
     * pg_get_expr} renders {@code timestamptz} in the session zone, so under a
     * non-UTC session an existing, correct partition renders as a different literal
     * and is wrongly reported as having incorrect bounds — and it must be restored
     * before the connection returns to the pool. HikariCP does not reset it, so a
     * leaked zone silently changes how every later borrower of that connection reads
     * and writes timestamps.
     *
     * <p>Uses a single-connection DataSource so "the connection that comes back" is
     * not a matter of pool luck.</p>
     */
    @Test
    void restoresTheSessionTimeZoneOnTheBorrowedConnection() throws Exception {
        PostgreSQLContainer<?> container = PostgresSupport.container();
        SingleConnectionDataSource single =
                new SingleConnectionDataSource(
                        container.getJdbcUrl(),
                        container.getUsername(),
                        container.getPassword(),
                        true);
        try {
            try (Connection connection = single.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("SET TIME ZONE 'Asia/Kolkata'");
            }

            PartitionPrecreationResult result =
                    new PartitionPrecreator(single)
                            .ensurePartitions(StatType.LORASTATS, SESSION_ZONE_START, 0);
            assertThat(result).isEqualTo(new PartitionPrecreationResult(true, 1));

            assertThat(sessionTimeZone(single)).isEqualTo("Asia/Kolkata");

            // And the comparison it protects still works: a second, idempotent run over
            // the same day must recognise the partition it just created.
            assertThat(
                            new PartitionPrecreator(single)
                                    .ensurePartitions(StatType.LORASTATS, SESSION_ZONE_START, 0))
                    .isEqualTo(new PartitionPrecreationResult(true, 1));
            assertThat(sessionTimeZone(single)).isEqualTo("Asia/Kolkata");
        } finally {
            single.destroy();
        }
    }

    private static String sessionTimeZone(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SHOW TimeZone")) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
