package com.zenner.datahub.stats.writer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zenner.datahub.stats.testsupport.PostgresSupport;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V7 (T-036) carries every invariant the lorastats table reached across V1..V5 and the
 * sysstats table took whole in V6, from its first row: the partition key and key order,
 * the two ADR-003 indexes and no third, {@code passthrough}, {@code data_extra}, and the
 * canonical-{@code clsbox} CHECK.
 */
@SpringBootTest(
        classes = StatsWriterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "stats.writer.stat-type=netstats",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "stats.retention.schedule=-",
            "stats.retention.pre-create-on-startup=false"
        })
@Import(PostgresSupport.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OmsstatsMigrationIntegrationTest {

    private static final String TEST_PARTITION = "omsstats_t036_migration_day";
    private static final OffsetDateTime DEVICE_TIME =
            OffsetDateTime.of(2099, 3, 1, 12, 0, 0, 0, ZoneOffset.UTC);

    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void createShortLivedDailyPartition() {
        jdbcTemplate.execute(
                """
                CREATE TABLE %s PARTITION OF omsstats
                FOR VALUES FROM ('2099-03-01 00:00:00+00') TO ('2099-03-02 00:00:00+00')
                """.formatted(TEST_PARTITION));
    }

    @AfterAll
    void removeShortLivedDailyPartition() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + TEST_PARTITION);
    }

    @Test
    void createsThePartitionedParentAsVersionSeven() {
        assertThat(jdbcTemplate.queryForObject("SELECT to_regclass('omsstats')", String.class))
                .isEqualTo("omsstats");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT pg_get_partkeydef('omsstats'::regclass)", String.class))
                .isEqualTo("RANGE (ts_device)");
        List<String> versions =
                jdbcTemplate.queryForList(
                        "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                        String.class);
        assertThat(versions).contains("7");
    }

    @Test
    void carriesExactlyTheAdr003IndexDirectionAndPropagatesItToPartitions() {
        for (String table : List.of("omsstats", TEST_PARTITION)) {
            List<String> indexes =
                    jdbcTemplate.queryForList(
                            "SELECT indexdef FROM pg_indexes "
                                    + "WHERE schemaname = current_schema() AND tablename = ? "
                                    + "ORDER BY indexname",
                            String.class,
                            table);
            assertThat(indexes)
                    .as("indexes on %s", table)
                    .hasSize(3)
                    .anySatisfy(index -> assertThat(index).contains("(ts_device, uid)"))
                    .anySatisfy(index -> assertThat(index).contains("(tenant, clsbox, ts_device DESC, uid DESC)"))
                    .anySatisfy(index -> assertThat(index).contains("(tenant, uid)"))
                    // The unreachable index V2 once shipped for lorastats is not repeated.
                    .noneSatisfy(index -> assertThat(index).contains("(ts_device DESC, uid DESC)"))
                    .noneSatisfy(index -> assertThat(index).contains("data_extra"));
        }
    }

    /** Every payload member is an integer on the wire, so every payload column is a NOT NULL bigint. */
    @Test
    void mapsEveryColumnToTheWireTypeAndNullability() {
        List<String> columns =
                jdbcTemplate.queryForList(
                        """
                        SELECT column_name || ':' || data_type || ':' || is_nullable
                        FROM information_schema.columns
                        WHERE table_schema = current_schema() AND table_name = 'omsstats'
                        ORDER BY ordinal_position
                        """,
                        String.class);

        assertThat(columns)
                .containsExactly(
                        "uid:text:NO",
                        "tenant:text:NO",
                        "maintenant:text:NO",
                        "clsbox:text:NO",
                        "ts_server:timestamp with time zone:NO",
                        "ts_device:timestamp with time zone:NO",
                        "received_at:timestamp with time zone:NO",
                        "record_version:smallint:YES",
                        "schema_version:smallint:NO",
                        "passthrough:jsonb:YES",
                        "data_extra:jsonb:YES",
                        "rx_nr_pkts:bigint:NO",
                        "rx_valid_nr_pkts:bigint:NO",
                        "rx_crc_bad:bigint:NO",
                        "device_count:bigint:NO",
                        "timespan:bigint:NO");
    }

    /**
     * ADR-004: there is no normalised-timestamp column. {@code ts.converter} is preserved
     * inside {@code passthrough} and nowhere else — a dedicated column would be the first
     * step toward acting on it.
     */
    @Test
    void hasNoColumnForANormalisedOrConverterTimestamp() {
        List<String> columns =
                jdbcTemplate.queryForList(
                        "SELECT column_name FROM information_schema.columns "
                                + "WHERE table_schema = current_schema() AND table_name = 'omsstats'",
                        String.class);
        assertThat(columns).noneMatch(column -> column.contains("converter") || column.contains("normal"));
    }

    @Test
    void acceptsTheMinimumRowAndRejectsThePartitionPrimaryKeyDuplicate() {
        insertMinimumRow("migration-test-uid", "A1B2C3D4E5F6");

        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM omsstats WHERE ts_device = ? AND uid = ? "
                                        + "AND received_at IS NOT NULL AND passthrough IS NULL AND data_extra IS NULL",
                                Integer.class,
                                DEVICE_TIME,
                                "migration-test-uid"))
                .isEqualTo(1);

        assertThatThrownBy(() -> insertMinimumRow("migration-test-uid", "A1B2C3D4E5F6"))
                .isInstanceOf(DuplicateKeyException.class)
                .satisfies(failure -> {
                    Throwable cause = ((DuplicateKeyException) failure).getMostSpecificCause();
                    assertThat(cause).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) cause).getSQLState()).isEqualTo("23505");
                });
    }

    /** DL-115: the table itself refuses a non-canonical clsbox, whatever bypassed the Writer. */
    @Test
    void theTableRefusesANonCanonicalClsboxEvenWhenTheWriterIsBypassed() {
        assertThatThrownBy(() -> insertMinimumRow("lowercase-clsbox", "a1b2c3d4e5f6"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(failure -> {
                    Throwable cause = ((DataIntegrityViolationException) failure).getMostSpecificCause();
                    assertThat(cause).isInstanceOf(SQLException.class);
                    // 23514 check_violation
                    assertThat(((SQLException) cause).getSQLState()).isEqualTo("23514");
                });
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM omsstats WHERE uid = 'lowercase-clsbox'",
                                Integer.class))
                .isZero();
    }

    private void insertMinimumRow(String uid, String clsbox) {
        jdbcTemplate.update(
                """
                INSERT INTO omsstats (
                    uid, tenant, maintenant, clsbox, ts_server, ts_device, schema_version,
                    rx_nr_pkts, rx_valid_nr_pkts, rx_crc_bad, device_count, timespan
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                uid,
                "tenant-test",
                "maintenant-test",
                clsbox,
                DEVICE_TIME,
                DEVICE_TIME,
                (short) 1,
                663L,
                105L,
                528L,
                16L,
                1800L);
    }
}
