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
 * V6 carries every invariant the lorastats table reached across V1..V5, from
 * its first row: the partition key and key order, the two indexes and no
 * third, {@code data_extra}, and the canonical-{@code clsbox} CHECK.
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
class SysstatsMigrationIntegrationTest {

    private static final String TEST_PARTITION = "sysstats_t035_migration_day";
    private static final OffsetDateTime DEVICE_TIME =
            OffsetDateTime.of(2099, 2, 1, 12, 0, 0, 0, ZoneOffset.UTC);

    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void createShortLivedDailyPartition() {
        jdbcTemplate.execute(
                """
                CREATE TABLE %s PARTITION OF sysstats
                FOR VALUES FROM ('2099-02-01 00:00:00+00') TO ('2099-02-02 00:00:00+00')
                """.formatted(TEST_PARTITION));
    }

    @AfterAll
    void removeShortLivedDailyPartition() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + TEST_PARTITION);
    }

    @Test
    void createsThePartitionedParentAsVersionSix() {
        assertThat(jdbcTemplate.queryForObject("SELECT to_regclass('sysstats')", String.class))
                .isEqualTo("sysstats");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT pg_get_partkeydef('sysstats'::regclass)", String.class))
                .isEqualTo("RANGE (ts_device)");
        List<String> versions =
                jdbcTemplate.queryForList(
                        "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                        String.class);
        assertThat(versions).contains("6");
    }

    @Test
    void carriesExactlyTheAdr003IndexDirectionAndPropagatesItToPartitions() {
        for (String table : List.of("sysstats", TEST_PARTITION)) {
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

    @Test
    void mapsEveryColumnToTheWireTypeAndNullability() {
        List<String> columns =
                jdbcTemplate.queryForList(
                        """
                        SELECT column_name || ':' || data_type || ':' || is_nullable
                        FROM information_schema.columns
                        WHERE table_schema = current_schema() AND table_name = 'sysstats'
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
                        "memfree:bigint:NO",
                        "uptime:double precision:NO",
                        "idletime:double precision:NO",
                        "load1:double precision:NO",
                        "load5:double precision:NO",
                        "load15:double precision:NO",
                        "runable:text:NO",
                        "temp:bigint:YES",
                        "rtt:bigint:YES",
                        "wan_if:text:YES",
                        "rebootcause:text:YES",
                        "timespan:bigint:YES");
    }

    @Test
    void acceptsTheMinimumRowAndRejectsThePartitionPrimaryKeyDuplicate() {
        insertMinimumRow("migration-test-uid", "A1B2C3D4E5F6");

        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM sysstats WHERE ts_device = ? AND uid = ? "
                                        + "AND received_at IS NOT NULL AND temp IS NULL AND timespan IS NULL",
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

    /** The table itself refuses a non-canonical clsbox, whatever bypassed the Writer. */
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
                                "SELECT count(*) FROM sysstats WHERE uid = 'lowercase-clsbox'",
                                Integer.class))
                .isZero();
    }

    private void insertMinimumRow(String uid, String clsbox) {
        jdbcTemplate.update(
                """
                INSERT INTO sysstats (
                    uid, tenant, maintenant, clsbox, ts_server, ts_device, schema_version,
                    memfree, uptime, idletime, load1, load5, load15, runable
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                uid,
                "tenant-test",
                "maintenant-test",
                clsbox,
                DEVICE_TIME,
                DEVICE_TIME,
                (short) 1,
                56812L,
                955641.36d,
                3632478.13d,
                0.41d,
                0.42d,
                0.38d,
                "2/139");
    }
}
