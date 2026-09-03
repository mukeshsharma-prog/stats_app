package com.zenner.datahub.stats.writer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zenner.datahub.stats.testsupport.PostgresSupport;
import java.sql.SQLException;
import java.sql.Timestamp;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies the immutable lorastats database migrations on PostgreSQL. */
@SpringBootTest(
        classes = StatsWriterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "stats.writer.stat-type=netstats",
            "stats.retention.schedule=-"
        })
@Import(PostgresSupport.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LorastatsMigrationIntegrationTest {

    private static final String TEST_PARTITION = "lorastats_t010_test_day";
    private static final OffsetDateTime DEVICE_TIME =
            OffsetDateTime.of(2099, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void createShortLivedDailyPartition() {
        jdbcTemplate.execute(
                """
                CREATE TABLE %s PARTITION OF lorastats
                FOR VALUES FROM ('2099-01-01 00:00:00+00') TO ('2099-01-02 00:00:00+00')
                """.formatted(TEST_PARTITION));
    }

    @AfterAll
    void removeShortLivedDailyPartition() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + TEST_PARTITION);
    }

    @Test
    void appliesAllMigrationsWithTheRequiredPartitionAndOnlyTheRequiredIndex() {
        assertThat(jdbcTemplate.queryForObject("SELECT to_regclass('lorastats')", String.class))
                .isEqualTo("lorastats");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT pg_get_partkeydef('lorastats'::regclass)", String.class))
                .isEqualTo("RANGE (ts_device)");

        List<String> versions =
                jdbcTemplate.queryForList(
                        "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                        String.class);
        assertThat(versions).containsExactly("1", "2");

        List<String> indexDefinitions =
                jdbcTemplate.queryForList(
                        "SELECT indexdef FROM pg_indexes "
                                + "WHERE schemaname = current_schema() AND tablename = 'lorastats' "
                                + "ORDER BY indexname",
                        String.class);
        assertThat(indexDefinitions)
                .hasSize(2)
                .anySatisfy(
                        index ->
                                assertThat(index)
                                        .contains("lorastats_pkey")
                                        .contains("(ts_device, uid)"))
                .anySatisfy(
                        index ->
                                assertThat(index)
                                        .contains("idx_lorastats_tsdevice_uid")
                                        .contains("(ts_device DESC, uid DESC)"));
        assertThat(indexDefinitions)
                .allSatisfy(
                        index ->
                                assertThat(index)
                                        .doesNotContain("tenant")
                                        .doesNotContain("clsbox")
                                        .doesNotContain("record_version")
                                        .doesNotContain("passthrough"));
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM pg_indexes "
                                        + "WHERE schemaname = current_schema() "
                                        + "AND tablename = 'lorastats' "
                                        + "AND indexname IN ("
                                        + "'idx_lorastats_tenant_clsbox_device_uid', 'idx_lorastats_tenant_uid')",
                                Integer.class))
                .isZero();
    }

    @Test
    void mapsEveryNamedSchemaPayloadFieldToTheRequiredBigintNullability() {
        List<String> payloadColumns =
                jdbcTemplate.queryForList(
                        """
                        SELECT column_name || ':' || data_type || ':' || is_nullable
                        FROM information_schema.columns
                        WHERE table_schema = current_schema()
                          AND table_name = 'lorastats'
                          AND column_name IN (
                              'rx_nr_pkts', 'rx_crc_missing', 'rx_crc_bad', 'rx_short_pkt',
                              'tx_success', 'tx_failed', 'tx_peek', 'tx_peek_index_zero',
                              'tx_override', 'tx_busy', 'tx_dequeue_error', 'tx_type_beacon',
                              'chip_comm_error', 'util_fopen_failed', 'fsk_received',
                              'rx_type_arol', 'timespan'
                          )
                        ORDER BY ordinal_position
                        """,
                        String.class);

        assertThat(payloadColumns)
                .containsExactly(
                        "rx_nr_pkts:bigint:NO",
                        "rx_crc_missing:bigint:NO",
                        "rx_crc_bad:bigint:NO",
                        "rx_short_pkt:bigint:YES",
                        "tx_success:bigint:NO",
                        "tx_failed:bigint:NO",
                        "tx_peek:bigint:NO",
                        "tx_peek_index_zero:bigint:NO",
                        "tx_override:bigint:NO",
                        "tx_busy:bigint:NO",
                        "tx_dequeue_error:bigint:NO",
                        "tx_type_beacon:bigint:NO",
                        "chip_comm_error:bigint:NO",
                        "util_fopen_failed:bigint:NO",
                        "fsk_received:bigint:YES",
                        "rx_type_arol:bigint:YES",
                        "timespan:bigint:YES");
    }

    @Test
    void acceptsTheMinimumRowUsesReceivedAtDefaultAndRejectsThePartitionPrimaryKeyDuplicate() {
        insertMinimumRow();

        Timestamp receivedAt =
                jdbcTemplate.queryForObject(
                        "SELECT received_at FROM lorastats WHERE ts_device = ? AND uid = ?",
                        Timestamp.class,
                        DEVICE_TIME,
                        "migration-test-uid");
        assertThat(receivedAt).isNotNull();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM lorastats "
                                        + "WHERE ts_device = ? AND uid = ? "
                                        + "AND rx_short_pkt IS NULL AND fsk_received IS NULL "
                                        + "AND rx_type_arol IS NULL AND timespan IS NULL",
                                Integer.class,
                                DEVICE_TIME,
                                "migration-test-uid"))
                .isEqualTo(1);

        assertThatThrownBy(this::insertMinimumRow)
                .isInstanceOf(DuplicateKeyException.class)
                .satisfies(
                        failure -> {
                            Throwable cause = ((DuplicateKeyException) failure).getMostSpecificCause();
                            assertThat(cause).isInstanceOf(SQLException.class);
                            assertThat(((SQLException) cause).getSQLState()).isEqualTo("23505");
                        });
    }

    private void insertMinimumRow() {
        jdbcTemplate.update(
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
                "migration-test-uid",
                "tenant-test",
                "maintenant-test",
                "A1B2C3D4E5F6",
                DEVICE_TIME,
                DEVICE_TIME,
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
}
