package com.zenner.datahub.stats.writer.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.testsupport.PostgresSupport;
import com.zenner.datahub.stats.writer.StatsWriterApplication;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The per-partition restore drill, TIMED against the agreed RTO of 1 h per partition, in
 * the exact shape the restore strategy names: recover ONE daily partition and re-attach it
 * WITHOUT taking the parent offline.
 *
 * <p>This is the executable half of {@code docs/runbooks/partition-restore.md}. It runs the
 * runbook's commands — {@code pg_dump}, {@code pg_restore -L} with the attach entries
 * removed, a matching CHECK, {@code ATTACH PARTITION}, the post-attach checks — inside the
 * real PostgreSQL 17 container, with a writer inserting into a neighbouring partition of
 * the same parent the whole time. The assertions are the runbook's stop conditions, and
 * the timing table it prints is where the measured numbers in the runbook come from.</p>
 *
 * <h2>What the drill rehearses that a plain {@code pg_restore} does not</h2>
 *
 * <p>{@code pg_dump -t <partition>} emits {@code CREATE TABLE}, then {@code ATTACH
 * PARTITION}, then the {@code COPY}, then the child's indexes. Restored as-is, the empty
 * child is attached to the live parent first — PostgreSQL creates the child's indexes at
 * that moment to match the parent's — so the data loads row by row into an already
 * visible, already indexed partition, and the dump's own {@code ADD CONSTRAINT} and
 * {@code CREATE INDEX} then fail ("errors ignored on restore: 2", exit status 1). The
 * rows are right; the exit code says otherwise; the day is half-visible to the API for the
 * whole load. The drill instead restores the child <em>standalone</em> (TOC entries
 * {@code TABLE ATTACH} and {@code INDEX ATTACH} dropped), lets {@code pg_restore} build the
 * indexes after the load, adds a CHECK that matches the bound so {@code ATTACH} skips its
 * validation scan, and attaches in one statement: the day appears atomically, the parent
 * holds only {@code SHARE UPDATE EXCLUSIVE} for under a millisecond, and the inserter
 * never notices.</p>
 */
@SpringBootTest(
        classes = StatsWriterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            // A type with no consumer, so no listener attaches to the shared broker.
            "stats.writer.stat-type=netstats",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            // The runbook's first precondition: the cron is paused, so the sweep cannot
            // drop the evidence between the restore and the check.
            "stats.retention.schedule=-",
            "stats.retention.pre-create-on-startup=false"
        })
@Import(PostgresSupport.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PartitionRestoreDrillIntegrationTest {

    private static final StatType TYPE = StatType.LORASTATS;
    /** Far enough ahead that no other test partitions it and no sweep can ever drop it. */
    private static final LocalDate LOST_DAY = LocalDate.of(2095, 5, 5);
    private static final LocalDate LIVE_DAY = LOST_DAY.plusDays(1);
    private static final String LOST = PartitionNaming.partitionName(TYPE, LOST_DAY);
    private static final String LIVE = PartitionNaming.partitionName(TYPE, LIVE_DAY);
    /** ~1/200 of a production day for one type (115.2M/day ÷ 5 types ≈ 23M): enough to time. */
    private static final int ROWS = 200_000;
    private static final Duration RTO_PER_PARTITION = Duration.ofHours(1);
    private static final String DUMP = "/tmp/restore-drill-" + LOST + ".dump";

    @Autowired private JdbcTemplate jdbc;

    private final Map<String, Duration> timings = new LinkedHashMap<>();

    @BeforeAll
    void seedTheDayThatWillBeLostAndTheDayThatStaysLive() {
        for (LocalDate day : List.of(LOST_DAY, LIVE_DAY)) {
            jdbc.execute(
                    "CREATE TABLE " + PartitionNaming.partitionName(TYPE, day)
                            + " PARTITION OF lorastats " + PartitionNaming.partitionBound(day));
        }
        jdbc.update(
                """
                INSERT INTO lorastats (
                    uid, tenant, maintenant, clsbox, ts_server, ts_device, schema_version,
                    rx_nr_pkts, rx_crc_missing, rx_crc_bad, tx_success, tx_failed, tx_peek,
                    tx_peek_index_zero, tx_override, tx_busy, tx_dequeue_error, tx_type_beacon,
                    chip_comm_error, util_fopen_failed)
                SELECT 'drill-' || g, 'tenant-drill', 'management-parent', 'A1B2C3D4E5F6',
                       t.ts, t.ts, 1, g, 0, 0, g, 0, 0, 0, 0, 0, 0, 0, 0, 0
                FROM generate_series(1, ?) AS g,
                     LATERAL (SELECT ?::timestamptz + (g * INTERVAL '100 milliseconds')) AS t(ts)
                """,
                ROWS,
                startOf(LOST_DAY));
        assertThat(rowsOnDay(LOST_DAY)).isEqualTo(ROWS);
    }

    @AfterAll
    void dropTheDrillPartitions() {
        jdbc.execute("DROP TABLE IF EXISTS " + LOST);
        jdbc.execute("DROP TABLE IF EXISTS " + LIVE);
    }

    @Test
    void recoversOneDailyPartitionAndReattachesItWhileThePartentKeepsTakingWrites() throws Exception {
        long drillStart = System.nanoTime();

        // --- 1. Backup: the per-partition logical dump the runbook's nightly job takes.
        timed("1 pg_dump partition (-Fc)", () -> sh(
                "pg_dump -U %s -d %s -t %s --no-owner -Fc -f %s".formatted(user(), db(), LOST, DUMP)));

        // --- 2. Loss: the partition is gone. The parent, and every other day, is not.
        timed("2 simulate loss (DROP TABLE)", () -> jdbc.execute("DROP TABLE " + LOST));
        assertThat(rowsOnDay(LOST_DAY)).isZero();
        assertThat(jdbc.queryForObject("SELECT to_regclass(?)", String.class, LOST)).isNull();

        // A writer keeps inserting into the neighbouring day for the whole recovery; its
        // per-statement latency is the evidence that the parent was never offline.
        Inserter inserter = new Inserter();
        Thread writer = new Thread(inserter, "restore-drill-live-writer");
        writer.start();
        try {
            // --- 3. Restore STANDALONE: the dump minus its attach entries.
            timed("3 pg_restore standalone (TOC without TABLE/INDEX ATTACH)", () -> sh(
                    ("pg_restore -l %s > /tmp/restore-drill.toc"
                            + " && grep -vE 'TABLE ATTACH|INDEX ATTACH' /tmp/restore-drill.toc > /tmp/restore-drill.staging.toc"
                            + " && pg_restore -U %s -d %s --no-owner --exit-on-error -L /tmp/restore-drill.staging.toc %s")
                            .formatted(DUMP, user(), db(), DUMP)));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + LOST, Long.class)).isEqualTo(ROWS);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM pg_inherits WHERE inhrelid = ?::regclass", Long.class, LOST))
                    .as("restored standalone: not yet a partition")
                    .isZero();
            assertThat(rowsOnDay(LOST_DAY)).as("the parent does not see it yet").isZero();

            // --- 4. Verify the restored table against the name it carries — the same
            // invariant PartitionRetention checks before it drops anything.
            timed("4 verify bound of restored rows", () -> {
                Long outside = jdbc.queryForObject(
                        "SELECT count(*) FROM " + LOST + " WHERE ts_device < ?::timestamptz OR ts_device >= ?::timestamptz",
                        Long.class,
                        startOf(LOST_DAY),
                        startOf(LOST_DAY.plusDays(1)));
                assertThat(outside).as("every restored row belongs to the day the name says").isZero();
            });

            // --- 5. A CHECK that matches the bound, so ATTACH skips its validation scan.
            timed("5 ADD CONSTRAINT matching CHECK", () -> jdbc.execute(
                    "ALTER TABLE " + LOST + " ADD CONSTRAINT restore_drill_bound CHECK ("
                            + "ts_device >= '" + startOf(LOST_DAY) + "'::timestamptz AND "
                            + "ts_device < '" + startOf(LOST_DAY.plusDays(1)) + "'::timestamptz)"));

            // --- 6. Re-attach. SHARE UPDATE EXCLUSIVE on the parent: inserts continue.
            timed("6 ATTACH PARTITION", () -> jdbc.execute(
                    "ALTER TABLE lorastats ATTACH PARTITION " + LOST + " " + PartitionNaming.partitionBound(LOST_DAY)));
            timed("7 DROP the scaffolding CHECK", () -> jdbc.execute(
                    "ALTER TABLE " + LOST + " DROP CONSTRAINT restore_drill_bound"));
        } finally {
            inserter.stop.set(true);
            writer.join(Duration.ofSeconds(30).toMillis());
        }
        Duration total = Duration.ofNanos(System.nanoTime() - drillStart);

        // --- 8. The post-attach checks the runbook requires before the drill is "done".
        assertThat(rowsOnDay(LOST_DAY)).as("the parent sees the whole day again").isEqualTo(ROWS);
        assertThat(jdbc.queryForObject(
                        "SELECT inhdetachpending FROM pg_inherits WHERE inhrelid = ?::regclass", Boolean.class, LOST))
                .as("not pending detach")
                .isFalse();
        assertThat(jdbc.queryForList(
                        "SELECT i.indexrelid::regclass::text FROM pg_index i WHERE i.indrelid = ?::regclass AND i.indisvalid",
                        String.class, LOST))
                .as("the child carries the parent's three indexes, all valid")
                .hasSize(3);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_inherits WHERE inhrelid IN "
                                + "(SELECT indexrelid FROM pg_index WHERE indrelid = ?::regclass)",
                        Long.class, LOST))
                .as("each child index is attached to its parent index")
                .isEqualTo(3L);
        assertThat(jdbc.queryForList(
                        "SELECT conname FROM pg_constraint WHERE conrelid = ?::regclass AND contype = 'c'",
                        String.class, LOST))
                .as("no scaffolding left behind")
                .noneMatch(name -> name.contains("restore_drill"));

        // --- 9. The parent was never offline.
        assertThat(inserter.failures.get()).as("inserts that failed during the recovery").isZero();
        assertThat(inserter.samples).as("the writer was actually writing").hasSizeGreaterThan(100);
        Duration worstInsert = Duration.ofNanos(Collections.max(inserter.samples));
        assertThat(worstInsert)
                .as("worst single INSERT into the neighbouring partition while the day was restored and re-attached")
                .isLessThan(Duration.ofSeconds(1));

        // --- 10. Timed against the signed RTO.
        report(total, inserter);
        assertThat(total).as("RTO 1 h per partition").isLessThan(RTO_PER_PARTITION);
    }

    // ---- helpers -----------------------------------------------------------

    /** Inserts into the LIVE day as fast as the round trip allows, recording each statement's latency. */
    private final class Inserter implements Runnable {
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicInteger failures = new AtomicInteger();
        final List<Long> samples = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void run() {
            int i = 0;
            while (!stop.get()) {
                i++;
                long start = System.nanoTime();
                try {
                    jdbc.update(
                            """
                            INSERT INTO lorastats (
                                uid, tenant, maintenant, clsbox, ts_server, ts_device, schema_version,
                                rx_nr_pkts, rx_crc_missing, rx_crc_bad, tx_success, tx_failed, tx_peek,
                                tx_peek_index_zero, tx_override, tx_busy, tx_dequeue_error, tx_type_beacon,
                                chip_comm_error, util_fopen_failed)
                            VALUES (?, 'tenant-drill', 'management-parent', 'A1B2C3D4E5F6', ?::timestamptz, ?::timestamptz, 1,
                                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                            """,
                            "live-" + i,
                            startOf(LIVE_DAY),
                            startOf(LIVE_DAY));
                } catch (RuntimeException failure) {
                    failures.incrementAndGet();
                }
                samples.add(System.nanoTime() - start);
            }
        }
    }

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    private void timed(String name, Step step) throws Exception {
        long start = System.nanoTime();
        step.run();
        timings.put(name, Duration.ofNanos(System.nanoTime() - start));
    }

    private void sh(String command) throws Exception {
        ExecResult result = container().execInContainer("sh", "-c", command);
        assertThat(result.getExitCode())
                .as("%s%nstdout: %s%nstderr: %s", command, result.getStdout(), result.getStderr())
                .isZero();
    }

    private long rowsOnDay(LocalDate day) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM lorastats WHERE ts_device >= ?::timestamptz AND ts_device < ?::timestamptz",
                Long.class,
                startOf(day),
                startOf(day.plusDays(1)));
    }

    private void report(Duration total, Inserter inserter) {
        List<Long> sorted = new ArrayList<>(inserter.samples);
        Collections.sort(sorted);
        StringBuilder out = new StringBuilder("\n[restore-drill] Partition restore drill — ")
                .append(LOST).append(", ").append(ROWS).append(" rows, PostgreSQL 17 (Testcontainers)\n");
        timings.forEach((step, took) -> out.append(String.format("[restore-drill]   %-58s %8.3f s%n", step, took.toNanos() / 1e9)));
        out.append(String.format("[restore-drill]   %-58s %8.3f s   (RTO %s)%n", "TOTAL", total.toNanos() / 1e9, RTO_PER_PARTITION));
        out.append(String.format(
                "[restore-drill]   concurrent inserts into %s: n=%d  p50=%.1f ms  p99=%.1f ms  max=%.1f ms  failures=0%n",
                LIVE, sorted.size(),
                sorted.get(sorted.size() / 2) / 1e6,
                sorted.get((int) (sorted.size() * 0.99)) / 1e6,
                sorted.get(sorted.size() - 1) / 1e6));
        System.out.print(out);
    }

    /** The literal start-of-day bound, UTC — what the FOR VALUES clause is built from. */
    private static String startOf(LocalDate day) {
        return day + " 00:00:00+00";
    }

    private static PostgreSQLContainer<?> container() {
        return PostgresSupport.container();
    }

    private static String user() {
        return container().getUsername();
    }

    private static String db() {
        return container().getDatabaseName();
    }
}
