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
 * "lorastats" found for row}; the T-020 manual-ACK contract correctly withholds the
 * ACK, so nothing is lost, but the consumer wedges once its prefetch window is full
 * and does not recover when the cron finally fires — those deliveries stay
 * unacknowledged until the process restarts.</p>
 *
 * <p>This boots the real application against real PostgreSQL with the cron disabled,
 * so the <em>only</em> thing that can have created a partition is the startup path.
 * It then writes a row dated today, which is the assertion that actually matters:
 * ingestion works the moment the module is up.</p>
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
            "stats.retention.pre-create-days=7"
        })
@Import({PostgresSupport.class, RabbitSupport.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PartitionBootstrapIntegrationTest {

    private static final DateTimeFormatter PARTITION_DATE =
            DateTimeFormatter.ofPattern("uuuu_MM_dd");

    private static final LocalDate TODAY_UTC = LocalDate.now(ZoneOffset.UTC);

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
    void startupCreatesTodayAndTheWholeConfiguredHorizonBeforeAnythingConsumes() {
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

        // pre-create-days=7 means today plus seven, so eight partitions.
        assertThat(actual).containsExactlyElementsOf(expected).hasSize(8);
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

    private static List<String> expectedPartitionNames() {
        List<String> names = new ArrayList<>();
        for (int offset = 0; offset <= 7; offset++) {
            names.add(
                    StatType.LORASTATS.wireName()
                            + "_"
                            + PARTITION_DATE.format(TODAY_UTC.plusDays(offset)));
        }
        return List.copyOf(names);
    }
}
