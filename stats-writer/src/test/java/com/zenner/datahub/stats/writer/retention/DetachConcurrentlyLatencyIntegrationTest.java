package com.zenner.datahub.stats.writer.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.testsupport.PostgresSupport;
import com.zenner.datahub.stats.writer.StatsWriterApplication;
import com.zenner.datahub.stats.writer.retention.PartitionRetention.RetentionResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code DETACH PARTITION … CONCURRENTLY} does not block the writer: MEASURED, not
 * assumed.
 *
 * <p>A plain {@code DETACH PARTITION} takes {@code ACCESS EXCLUSIVE} on the <em>parent</em>,
 * which is why the built sweep uses {@code CONCURRENTLY}. This test runs a writer inserting
 * into the current day's partition as fast as the round trip allows and records every
 * statement's latency, while:</p>
 *
 * <ol>
 *   <li><strong>baseline</strong> — nothing else happens;</li>
 *   <li><strong>the real sweep</strong> detaches and drops a 300,000-row day;</li>
 *   <li><strong>the real sweep with an open reader on the parent</strong> — the case the
 *       tracker warns turns a measurement into a mis-diagnosis: {@code DETACH CONCURRENTLY}
 *       <em>waits</em> for transactions that reference the partition, so the sweep looks
 *       slow and the cause is the harness. The writer must still be unaffected;</li>
 *   <li><strong>the control</strong> — a plain {@code DETACH} behind the same open reader.
 *       It queues for {@code ACCESS EXCLUSIVE} on the parent and every insert queues behind
 *       it for as long as the reader holds. If this phase did not stall the writer the
 *       measurement could not see blocking at all, and phases 2–3 would prove nothing.</li>
 * </ol>
 *
 * <p>Alongside the latencies a sampler counts, ten times a second, how many backends are
 * waiting on a lock — the other half of what the task asks to record. And every phase ends
 * with the trap-27 check: nothing left {@code inhdetachpending}.</p>
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
class DetachConcurrentlyLatencyIntegrationTest {

    private static final StatType TYPE = StatType.LORASTATS;
    private static final int RETENTION_DAYS = 90;
    private static final int OLD_ROWS = 300_000;
    /** A day no other test partitions; the writer's target. */
    private static final LocalDate LIVE_DAY = LocalDate.of(2095, 7, 7);
    private static final Duration READER_HOLD = Duration.ofMillis(1_500);

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    private RetentionWindow window;
    private PartitionRetention retention;
    private LocalDate oldDay;
    private final List<String> report = new ArrayList<>();

    @BeforeEach
    void buildTheSweepAndTheDays() {
        RetentionProperties properties =
                new RetentionProperties(
                        RetentionProperties.PartitionGranularity.DAILY, 7, "-", RETENTION_DAYS, Map.of(), Duration.ZERO);
        window = new RetentionWindow(properties, Clock.systemUTC());
        RetentionMetrics metrics = new RetentionMetrics(new SimpleMeterRegistry());
        metrics.registerFor(TYPE);
        retention = new PartitionRetention(dataSource, window, metrics);
        oldDay = window.cutoffDay(TYPE).minusDays(1);
        dropDays();
        jdbc.execute("CREATE TABLE " + name(LIVE_DAY) + " PARTITION OF lorastats " + PartitionNaming.partitionBound(LIVE_DAY));
    }

    @AfterEach
    void leaveNothingBehind() {
        dropDays();
        assertThat(pendingDetaches()).as("nothing left pending detach").isZero();
    }

    @Test
    void theSweepNeverStallsTheWriterAndTheControlDoes() throws Exception {
        Latencies baseline = measure("1 baseline (writer alone)", () -> Thread.sleep(2_000));

        seedOldDay();
        Latencies concurrently = measure("2 sweep: DETACH CONCURRENTLY + DROP of " + OLD_ROWS + " rows", () -> {
            RetentionResult result = retention.sweep(TYPE);
            assertThat(result.lockAcquired()).isTrue();
            assertThat(result.partitionsDropped()).isEqualTo(1);
        });
        assertThat(exists(oldDay)).isFalse();

        seedOldDay();
        Latencies concurrentlyBehindReader = measure(
                "3 sweep while a reader holds an open transaction on the parent (" + READER_HOLD.toMillis() + " ms)",
                () -> withOpenReader(() -> {
                    RetentionResult result = retention.sweep(TYPE);
                    assertThat(result.partitionsDropped()).isEqualTo(1);
                }));
        assertThat(exists(oldDay)).isFalse();

        seedOldDay();
        Latencies plainBehindReader = measure(
                "4 CONTROL: plain DETACH (ACCESS EXCLUSIVE) behind the same reader",
                () -> withOpenReader(() -> {
                    jdbc.execute("ALTER TABLE lorastats DETACH PARTITION " + name(oldDay));
                    jdbc.execute("DROP TABLE " + name(oldDay));
                }));
        assertThat(exists(oldDay)).isFalse();

        System.out.println("\n[detach-latency] DETACH CONCURRENTLY against a live writer — PostgreSQL 17 (Testcontainers)");
        report.forEach(line -> System.out.println("[detach-latency]   " + line));

        // The sweep — with or without a reader in the way — never made an insert wait
        // more than a short multiple of the baseline's worst case.
        Duration ceiling = Duration.ofMillis(Math.max(250, 5 * baseline.max().toMillis()));
        assertThat(concurrently.max()).as("phase 2: worst insert during the sweep").isLessThan(ceiling);
        assertThat(concurrently.failures).isZero();
        assertThat(concurrentlyBehindReader.max()).as("phase 3: worst insert during the sweep behind a reader").isLessThan(ceiling);
        assertThat(concurrentlyBehindReader.failures).isZero();
        assertThat(concurrentlyBehindReader.insertsWaiting).as("phase 3: no insert ever queued on the parent").isZero();
        // Phase 3 was slower for the SWEEP, not the writer: that is the harness waiting.
        assertThat(concurrentlyBehindReader.phase).isGreaterThan(READER_HOLD.minusMillis(200));
        // ...and the control proves the measurement can see a stall when there is one.
        assertThat(plainBehindReader.max()).as("phase 4: a plain DETACH stalls every insert behind the reader")
                .isGreaterThan(READER_HOLD.minusMillis(500));
        assertThat(plainBehindReader.insertsWaiting).as("phase 4: an insert was seen waiting on the parent's lock").isPositive();
    }

    // ---- the measurement ---------------------------------------------------

    private record Latencies(String phase_name, Duration phase, List<Long> samples, int failures, int lockWaiters, int insertsWaiting) {
        Duration max() {
            return Duration.ofNanos(Collections.max(samples));
        }

        Duration percentile(double p) {
            List<Long> sorted = new ArrayList<>(samples);
            Collections.sort(sorted);
            return Duration.ofNanos(sorted.get((int) Math.min(sorted.size() - 1, sorted.size() * p)));
        }
    }

    @FunctionalInterface
    private interface Phase {
        void run() throws Exception;
    }

    /** Runs {@code phase} with the writer and the lock sampler live, and records what they saw. */
    private Latencies measure(String label, Phase phase) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger maxWaiters = new AtomicInteger();
        AtomicInteger maxInsertsWaiting = new AtomicInteger();
        List<Long> samples = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch writerRunning = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> writer = pool.submit(() -> {
            int i = 0;
            while (!stop.get()) {
                long start = System.nanoTime();
                try {
                    String stamp = LIVE_DAY + " 00:00:00+00";
                    jdbc.update(INSERT, label.substring(0, 1) + "-" + (i++), stamp, stamp);
                } catch (RuntimeException failure) {
                    failures.incrementAndGet();
                }
                samples.add(System.nanoTime() - start);
                writerRunning.countDown();
            }
        });
        Future<?> sampler = pool.submit(() -> {
            while (!stop.get()) {
                try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
                        var r = s.executeQuery(
                                "SELECT count(*), count(*) FILTER (WHERE query ILIKE 'INSERT%') "
                                        + "FROM pg_stat_activity WHERE wait_event_type = 'Lock'")) {
                    r.next();
                    // Total waiters includes the DETACH itself when it waits for a reader;
                    // the second count is the one that says whether the WRITER waited.
                    maxWaiters.accumulateAndGet(r.getInt(1), Math::max);
                    maxInsertsWaiting.accumulateAndGet(r.getInt(2), Math::max);
                } catch (Exception ignored) {
                    // a failed sample is a missed sample, not a result
                }
                Thread.sleep(100);
            }
            return null;
        });
        assertThat(writerRunning.await(10, TimeUnit.SECONDS)).isTrue();
        long start = System.nanoTime();
        Duration took;
        try {
            phase.run();
        } finally {
            took = Duration.ofNanos(System.nanoTime() - start);
            stop.set(true);
            writer.get(30, TimeUnit.SECONDS);
            sampler.get(30, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
        assertThat(samples).as("the writer produced samples in " + label).isNotEmpty();
        Latencies result = new Latencies(label, took, List.copyOf(samples), failures.get(), maxWaiters.get(), maxInsertsWaiting.get());
        report.add(String.format(
                "%-78s phase=%6.3f s  inserts=%5d  p50=%6.1f ms  p99=%7.1f ms  max=%7.1f ms  failures=%d  lock waiters: any=%d inserts=%d",
                label, took.toNanos() / 1e9, samples.size(),
                result.percentile(0.5).toNanos() / 1e6, result.percentile(0.99).toNanos() / 1e6,
                result.max().toNanos() / 1e6, failures.get(), maxWaiters.get(), maxInsertsWaiting.get()));
        return result;
    }

    /** Holds an open transaction that has read the parent, for READER_HOLD, around {@code body}. */
    private void withOpenReader(Phase body) throws Exception {
        CountDownLatch readerHasRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                try (Statement s = c.createStatement()) {
                    s.executeQuery("SELECT count(*) FROM lorastats WHERE ts_device >= '" + oldDay
                            + " 00:00:00+00'::timestamptz AND ts_device < '" + oldDay.plusDays(1) + " 00:00:00+00'::timestamptz").close();
                }
                readerHasRead.countDown();
                release.await(READER_HOLD.toMillis(), TimeUnit.MILLISECONDS);
                c.rollback();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }, "t055-open-reader");
        reader.start();
        assertThat(readerHasRead.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            body.run();
        } finally {
            release.countDown();
            reader.join(10_000);
        }
    }

    // ---- fixtures ----------------------------------------------------------

    private static final String INSERT =
            """
            INSERT INTO lorastats (
                uid, tenant, maintenant, clsbox, ts_server, ts_device, schema_version,
                rx_nr_pkts, rx_crc_missing, rx_crc_bad, tx_success, tx_failed, tx_peek,
                tx_peek_index_zero, tx_override, tx_busy, tx_dequeue_error, tx_type_beacon,
                chip_comm_error, util_fopen_failed)
            VALUES (?, 'tenant-t055', 'management-parent', 'A1B2C3D4E5F6', ?::timestamptz, ?::timestamptz, 1,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
            """;

    private void seedOldDay() {
        jdbc.execute("CREATE TABLE " + name(oldDay) + " PARTITION OF lorastats " + PartitionNaming.partitionBound(oldDay));
        jdbc.update(
                """
                INSERT INTO lorastats (
                    uid, tenant, maintenant, clsbox, ts_server, ts_device, schema_version,
                    rx_nr_pkts, rx_crc_missing, rx_crc_bad, tx_success, tx_failed, tx_peek,
                    tx_peek_index_zero, tx_override, tx_busy, tx_dequeue_error, tx_type_beacon,
                    chip_comm_error, util_fopen_failed)
                SELECT 'old-' || g, 'tenant-t055', 'management-parent', 'A1B2C3D4E5F6',
                       t.ts, t.ts, 1, g, 0, 0, g, 0, 0, 0, 0, 0, 0, 0, 0, 0
                FROM generate_series(1, ?) AS g,
                     LATERAL (SELECT ?::timestamptz + (g * INTERVAL '250 milliseconds')) AS t(ts)
                """,
                OLD_ROWS,
                oldDay + " 00:00:00+00");
        assertThat(exists(oldDay)).isTrue();
    }

    private void dropDays() {
        for (LocalDate day : List.of(oldDay, LIVE_DAY)) {
            jdbc.execute("DROP TABLE IF EXISTS " + name(day));
        }
    }

    private boolean exists(LocalDate day) {
        return jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, name(day));
    }

    private int pendingDetaches() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_inherits WHERE inhdetachpending", Integer.class);
    }

    private static String name(LocalDate day) {
        return PartitionNaming.partitionName(TYPE, day);
    }
}
