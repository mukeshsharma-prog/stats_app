package com.zenner.datahub.stats.writer.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.testsupport.PostgresSupport;
import com.zenner.datahub.stats.testsupport.RabbitSupport;
import com.zenner.datahub.stats.writer.StatsWriterApplication;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The regression test for the deployment-time gap.
 *
 * <p>Before {@link PartitionBootstrap} the only trigger was the retention cron, so a
 * module deployed at any other hour came up against a parent table with no
 * partitions. Every insert then fails with {@code no partition of relation
 * "lorastats" found for row}; the manual-ACK contract correctly withholds the
 * ACK, so nothing is lost, but the consumer wedges once its prefetch window is full
 * and does not recover when the cron finally fires — those deliveries stay
 * unacknowledged until the process restarts.</p>
 *
 * <p>This boots the real application against real PostgreSQL with the cron disabled,
 * so the <em>only</em> thing that can have created a partition is the startup path.
 * It then writes a row dated today, which is the assertion that actually matters:
 * ingestion works the moment the module is up.</p>
 *
 * <p><strong>And a row dated three weeks ago.</strong> Ahead-only pre-creation
 * closed the wedge above and opened a quieter one: a valid record inside the retention
 * window but before the deployment's first day had no partition, failed its INSERT with
 * SQLSTATE 23514 and was dead-lettered as permanent — the gateway-back-from-an-outage
 * path retention on {@code ts.device} makes normal, lost with every counter reading healthy. Startup now
 * ensures the whole window, {@code [cutoffDay, today + pre-create-days]}.</p>
 */
@SpringBootTest(
        classes = StatsWriterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "stats.writer.stat-type=lorastats",
            "stats.consumer.lorastats.concurrency=1",
            "stats.consumer.lorastats.prefetch=1",
            // Disabled on purpose: no scheduled run can fire during this test, so a
            // partition existing proves the startup path created it.
            "stats.retention.schedule=-",
            "stats.retention.pre-create-on-startup=true",
            "stats.retention.pre-create-days=7",
            // Short on purpose: the whole window is ensured, and 30 days keeps the
            // partition set this test creates and drops readable. The three-weeks-old
            // record below sits inside it; a 31-day-old one would not.
            "stats.retention.default-days=30"
        })
@Import({PostgresSupport.class, RabbitSupport.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PartitionBootstrapIntegrationTest {

    private static final DateTimeFormatter PARTITION_DATE =
            DateTimeFormatter.ofPattern("uuuu_MM_dd");

    private static final LocalDate TODAY_UTC = LocalDate.now(ZoneOffset.UTC);

    private static final int RETENTION_DAYS = 30;
    private static final int PRE_CREATE_DAYS = 7;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @AfterAll
    void dropPartitionsCreatedAtStartup() {
        for (String name : expectedPartitionNames()) {
            jdbc.execute("DROP TABLE IF EXISTS " + name);
        }
    }

    @Test
    void startupCreatesTheWholeRetentionWindowAndHorizonBeforeAnythingConsumes() {
        List<String> expected = expectedPartitionNames();

        List<String> actual =
                jdbc.queryForList(
                        "SELECT child.relname FROM pg_inherits inheritance "
                                + "JOIN pg_class child ON child.oid = inheritance.inhrelid "
                                + "WHERE inheritance.inhparent = to_regclass(?) "
                                + "AND child.relname = ANY (?) ORDER BY child.relname",
                        String.class,
                        StatType.LORASTATS.wireName(),
                        expected.toArray(String[]::new));

        // The cutoff day through today plus seven: 30 + 7 + 1 partitions.
        assertThat(actual)
                .containsExactlyElementsOf(expected)
                .hasSize(RETENTION_DAYS + PRE_CREATE_DAYS + 1);
    }

    /**
     * The whole-window regression: a record measured 21 days ago — a gateway back from three
     * weeks offline — inserts on a deployment that is minutes old. Before the fix this
     * was {@code no partition of relation "lorastats" found for row}, SQLSTATE 23514,
     * straight to the DLQ as a permanent failure.
     */
    @Test
    void aRecordThreeWeeksOldInsertsOnADeploymentThatIsMinutesOld() {
        assertThat(insertDatedDaysAgo("bootstrap-late-" + TODAY_UTC, 21)).isEqualTo(1);
    }

    /**
     * The boundary belongs to the retained side: the cutoff day itself is
     * retained, so it has a partition. That the range starts <em>exactly</em> there and
     * not a day earlier is asserted against the precreator call in
     * {@link PartitionMaintenanceTest}; the shared test database may hold partitions
     * other contexts created, so "the day before has none" is not provable here.
     */
    @Test
    void theCutoffDayItselfHasAPartition() {
        assertThat(insertDatedDaysAgo("bootstrap-cutoff-" + TODAY_UTC, RETENTION_DAYS))
                .isEqualTo(1);
    }

    /**
     * The assertion that matters operationally: a record dated <em>now</em> inserts.
     * This is precisely what failed for up to a day before the startup path existed.
     */
    @Test
    void aRecordDatedNowInsertsImmediatelyAfterStartup() {
        int inserted =
                jdbc.update(
                        """
                        INSERT INTO lorastats (
                            uid, tenant, maintenant, clsbox, ts_server, ts_device,
                            schema_version, rx_nr_pkts, rx_crc_missing, rx_crc_bad,
                            tx_success, tx_failed, tx_peek, tx_peek_index_zero,
                            tx_override, tx_busy, tx_dequeue_error, tx_type_beacon,
                            chip_comm_error, util_fopen_failed
                        ) VALUES (
                            ?, ?, ?, ?, now(), now(),
                            1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
                        )
                        """,
                        "bootstrap-proof-" + TODAY_UTC,
                        "tenant-a",
                        "management-parent",
                        "A1B2C3D4E5F6");

        assertThat(inserted).isEqualTo(1);
    }

    /**
     * The listener is registered and startable — this is a writer that really would
     * have consumed into the unpartitioned table. Without a live consumer in the
     * context the partition assertions above would be proving nothing about the race
     * they exist to close.
     */
    @Test
    void theListenerThisProtectsIsActuallyPresent() {
        MessageListenerContainer container =
                listenerRegistry.getListenerContainer("stats-writer-lorastats");

        assertThat(container).isNotNull();
        assertThat(container.isRunning()).isTrue();
    }

    private int insertDatedDaysAgo(String uid, int daysAgo) {
        return jdbc.update(
                """
                INSERT INTO lorastats (
                    uid, tenant, maintenant, clsbox, ts_server, ts_device,
                    schema_version, rx_nr_pkts, rx_crc_missing, rx_crc_bad,
                    tx_success, tx_failed, tx_peek, tx_peek_index_zero,
                    tx_override, tx_busy, tx_dequeue_error, tx_type_beacon,
                    chip_comm_error, util_fopen_failed
                ) VALUES (
                    ?, ?, ?, ?, now(), now() - make_interval(days => ?),
                    1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
                )
                """,
                uid,
                "tenant-a",
                "management-parent",
                "A1B2C3D4E5F6",
                daysAgo);
    }

    private static List<String> expectedPartitionNames() {
        List<String> names = new ArrayList<>();
        LocalDate cutoffDay = TODAY_UTC.minusDays(RETENTION_DAYS);
        for (int offset = 0; offset <= RETENTION_DAYS + PRE_CREATE_DAYS; offset++) {
            names.add(
                    StatType.LORASTATS.wireName()
                            + "_"
                            + PARTITION_DATE.format(cutoffDay.plusDays(offset)));
        }
        return List.copyOf(names);
    }
}
