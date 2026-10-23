package com.zenner.datahub.stats.writer.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.testsupport.PostgresSupport;
import com.zenner.datahub.stats.writer.StatsWriterApplication;
import com.zenner.datahub.stats.writer.retention.PartitionRetention.RetentionResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Partition retention and its boundary, against the real PostgreSQL 17 the mechanism depends on.
 *
 * <p><strong>Every drop here happens through {@code DETACH PARTITION … CONCURRENTLY}
 * followed by {@code DROP TABLE}, and that is itself an assertion.</strong>
 * {@code DETACH CONCURRENTLY} is refused outright inside a transaction block, so a sweep
 * that ran under a transaction would fail every one of these tests rather than quietly
 * degrade. Equally, the boundary must be proved "via the ACTUAL mechanism — a
 * dropped partition, not a simulated delete": the rows below disappear because their
 * partition ceased to exist, and the assertions read the parent table to say so.</p>
 *
 * <p>Dates are derived from the configured window rather than written as literals, so the
 * boundary cases stay boundary cases on every day the suite runs. Everything created here
 * sits either one day outside the cutoff or thirty days in the future, which no other test
 * in this module touches.</p>
 */
@SpringBootTest(
        classes = StatsWriterApplication.class,
        properties = {
            // Follows PartitionPrecreatorIntegrationTest: no bootstrap, no cron, and no
            // lorastats consumer, so this context needs no broker. The partitions under
            // test are created and removed by the test itself.
            "stats.writer.stat-type=netstats",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "stats.retention.schedule=-",
            "stats.retention.pre-create-on-startup=false"
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresSupport.class)
class PartitionRetentionIntegrationTest {

    private static final StatType TYPE = StatType.LORASTATS;
    private static final int RETENTION_DAYS = 90;

    @Autowired private DataSource dataSource;

    @Autowired private JdbcTemplate jdbcTemplate;

    private SimpleMeterRegistry registry;
    private RetentionMetrics metrics;
    private RetentionWindow window;
    private PartitionRetention retention;

    private LocalDate droppableDay;
    private LocalDate olderDroppableDay;
    private LocalDate boundaryDay;
    private LocalDate futureDay;

    @BeforeEach
    void buildTheSweepAndItsDays() {
        registry = new SimpleMeterRegistry();
        metrics = new RetentionMetrics(registry);
        RetentionProperties properties =
                new RetentionProperties(
                        RetentionProperties.PartitionGranularity.DAILY,
                        7,
                        "-",
                        RETENTION_DAYS,
                        Map.of(),
                        Duration.ZERO);
        window = new RetentionWindow(properties, Clock.systemUTC());
        retention = new PartitionRetention(dataSource, window, metrics);
        // What PartitionMaintenance.ensureBeforeIngestion() does at startup, so every
        // series exports at zero. "This counter did NOT move" is only an assertion
        // against a counter that exists.
        metrics.registerFor(TYPE);

        boundaryDay = window.cutoffDay(TYPE);
        droppableDay = boundaryDay.minusDays(1);
        olderDroppableDay = boundaryDay.minusDays(5);
        futureDay = window.today().plusDays(30);
        removeTestPartitions();
    }

    @AfterEach
    void cleanUp() {
        removeTestPartitions();
    }

    private void removeTestPartitions() {
        for (LocalDate day : List.of(droppableDay, olderDroppableDay, boundaryDay, futureDay)) {
            // DETACH first in case a test left a child attached; DROP handles both.
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + partitionName(day));
        }
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + partitionName(droppableDay) + "_manual");
    }

    // === The mechanism =====================================================

    /**
     * The core assertion, at the boundary: the partition one day outside the window is
     * detached and dropped, the partition <em>on</em> the cutoff day is kept, and the rows
     * follow their partitions.
     */
    @Test
    void dropsThePartitionJustOutsideTheWindowAndKeepsTheOneOnTheBoundary() {
        createPartition(droppableDay);
        createPartition(boundaryDay);
        insertRow(droppableDay, "outside-the-window");
        insertRow(boundaryDay, "inside-the-window");

        RetentionResult result = retention.sweep(TYPE);

        assertThat(result.lockAcquired()).isTrue();
        assertThat(result.partitionsDropped()).isEqualTo(1);
        assertThat(result.cutoffDay()).isEqualTo(boundaryDay);
        assertThat(partitionExists(droppableDay)).isFalse();
        assertThat(partitionExists(boundaryDay)).isTrue();
        // Read through the PARENT: the row is gone because its partition is, which is the
        // difference between this and a DELETE that happened to remove the same row.
        assertThat(uidsInParent()).contains("inside-the-window").doesNotContain("outside-the-window");
        assertThat(counter(RetentionMetrics.PARTITIONS_DROPPED)).isEqualTo(1.0d);
    }

    /**
     * Retention must never race pre-creation for the same day. Today's partition is the
     * one being written to right now, and a future partition is one pre-creation just made.
     */
    @Test
    void neverDropsAFuturePartition() {
        createPartition(futureDay);

        RetentionResult result = retention.sweep(TYPE);

        assertThat(result.partitionsDropped()).isZero();
        assertThat(partitionExists(futureDay)).isTrue();
    }

    /** After an outage the backlog can be several days, and a cap would leave the rest. */
    @Test
    void dropsEveryPartitionPastTheWindowInOneRun() {
        createPartition(droppableDay);
        createPartition(olderDroppableDay);
        createPartition(boundaryDay);

        RetentionResult result = retention.sweep(TYPE);

        assertThat(result.partitionsDropped()).isEqualTo(2);
        assertThat(partitionExists(droppableDay)).isFalse();
        assertThat(partitionExists(olderDroppableDay)).isFalse();
        assertThat(partitionExists(boundaryDay)).isTrue();
    }

    // === What it refuses to touch ==========================================

    /**
     * A child this module did not name is left alone, permanently. Retention drops tables;
     * a relation it cannot account for belongs to somebody who did not write it down.
     */
    @Test
    void refusesToDropAChildWhoseNameItDidNotCreate() {
        String manual = partitionName(droppableDay) + "_manual";
        jdbcTemplate.execute(
                "CREATE TABLE "
                        + manual
                        + " PARTITION OF "
                        + TYPE.wireName()
                        + " "
                        + bound(droppableDay));

        RetentionResult result = retention.sweep(TYPE);

        assertThat(result.partitionsDropped()).isZero();
        assertThat(relationExists(manual)).isTrue();
    }

    /**
     * A name that says one day and a bound that says another means dropping it would
     * delete a range nobody expects. The refusal is the assertion.
     */
    @Test
    void refusesToDropAPartitionWhoseBoundsDisagreeWithItsName() {
        // Named for the droppable day, actually covering the five days before it.
        jdbcTemplate.execute(
                "CREATE TABLE "
                        + partitionName(droppableDay)
                        + " PARTITION OF "
                        + TYPE.wireName()
                        + " FOR VALUES FROM ('"
                        + olderDroppableDay
                        + " 00:00:00+00') TO ('"
                        + droppableDay.plusDays(1)
                        + " 00:00:00+00')");

        RetentionResult result = retention.sweep(TYPE);

        assertThat(result.partitionsDropped()).isZero();
        assertThat(partitionExists(droppableDay)).isTrue();
    }

    // === Recovering from a half-finished run ===============================

    /**
     * The failure that motivated this class. An interrupted {@code DETACH CONCURRENTLY}
     * leaves the partition "pending detach", and from then on <em>every</em> detach on that
     * parent fails — while {@code INSERT} and pre-creation carry on working, so nothing
     * about ingestion looks wrong and the only symptom is disk. The sweep finalises it
     * first, so a run killed by a rolling deploy costs a day, not a permanent wedge.
     */
    @Test
    void finalisesADetachAnEarlierRunLeftPendingAndThenReclaimsIt() throws Exception {
        createPartition(droppableDay);
        createPartition(olderDroppableDay);
        leavePendingDetach(droppableDay);

        assertThat(detachIsPending(droppableDay))
                .as("the test could not reproduce the pending-detach state it exists to cover")
                .isTrue();

        RetentionResult result = retention.sweep(TYPE);

        assertThat(result.detachesFinalised()).isEqualTo(1);
        // Finalising makes it an orphan; the same run reclaims it.
        assertThat(relationExists(partitionName(droppableDay))).isFalse();
        assertThat(partitionExists(olderDroppableDay)).isFalse();
        assertThat(counter(RetentionMetrics.ORPHANS_DROPPED)).isEqualTo(1.0d);
    }

    /**
     * The other half of the same window: detached, then the process died before the drop.
     * The table keeps its rows and its indexes and nothing would ever reclaim it, so it is
     * counted separately — an ordinary drop reads as retention working, and this does not.
     */
    @Test
    void reclaimsATableThatAnEarlierRunDetachedButNeverDropped() {
        createPartition(droppableDay);
        insertRow(droppableDay, "left-behind");
        jdbcTemplate.execute(
                "ALTER TABLE "
                        + TYPE.wireName()
                        + " DETACH PARTITION "
                        + partitionName(droppableDay));
        assertThat(relationExists(partitionName(droppableDay))).isTrue();

        RetentionResult result = retention.sweep(TYPE);

        assertThat(result.orphansDropped()).isEqualTo(1);
        assertThat(result.partitionsDropped()).isZero();
        assertThat(relationExists(partitionName(droppableDay))).isFalse();
        assertThat(counter(RetentionMetrics.ORPHANS_DROPPED)).isEqualTo(1.0d);
        assertThat(counter(RetentionMetrics.PARTITIONS_DROPPED)).isZero();
    }

    /** A detached table still inside the window was detached by a person, so leave it. */
    @Test
    void leavesADetachedTableThatIsStillInsideTheWindow() {
        createPartition(futureDay);
        jdbcTemplate.execute(
                "ALTER TABLE " + TYPE.wireName() + " DETACH PARTITION " + partitionName(futureDay));

        RetentionResult result = retention.sweep(TYPE);

        assertThat(result.orphansDropped()).isZero();
        assertThat(relationExists(partitionName(futureDay))).isTrue();
    }

    // === Concurrency and metering ==========================================

    /**
     * Production runs N replicas per stat type, so without the lock N replicas would
     * detach the same partition at once. Losing the race is the normal outcome, not a
     * failure — and a replica that lost it must change nothing.
     */
    @Test
    void skipsTheRunEntirelyWhenAnotherReplicaHoldsTheAdvisoryLock() throws Exception {
        createPartition(droppableDay);

        try (Connection holder = dataSource.getConnection();
                Statement statement = holder.createStatement()) {
            statement.execute(
                    "SELECT pg_advisory_lock(hashtext('stats.retention:" + TYPE.wireName() + "'))");

            RetentionResult result = retention.sweep(TYPE);

            assertThat(result.lockAcquired()).isFalse();
            assertThat(result.changedAnything()).isFalse();
            assertThat(partitionExists(droppableDay)).isTrue();
            // The loser did not sweep, so it must not say it did.
            assertThat(gauge(RetentionMetrics.LAST_SWEEP_TIMESTAMP)).isNaN();
        }
    }

    /**
     * The counters cannot report an absence: a sweep that has stopped and a sweep with
     * nothing to do both stop incrementing. The gauge is what climbs.
     */
    @Test
    void publishesTheAgeOfTheOldestSurvivingPartition() {
        createPartition(droppableDay);
        createPartition(boundaryDay);
        // Before the first sweep neither gauge claims anything. A zero here read
        // as "healthy" on a replica whose daily pass had not run for 29 hours.
        assertThat(gauge(RetentionMetrics.OLDEST_PARTITION_AGE_DAYS)).isNaN();
        assertThat(gauge(RetentionMetrics.LAST_SWEEP_TIMESTAMP)).isNaN();
        long before = java.time.Instant.now().getEpochSecond();

        retention.sweep(TYPE);

        assertThat(gauge(RetentionMetrics.LAST_SWEEP_TIMESTAMP))
                .as("the completed sweep stamped its time")
                .isBetween((double) before, (double) java.time.Instant.now().getEpochSecond() + 1);

        assertThat(
                        registry.get(RetentionMetrics.OLDEST_PARTITION_AGE_DAYS)
                                .tag("type", TYPE.wireName())
                                .gauge()
                                .value())
                .as("the oldest partition left standing is the cutoff day itself")
                .isEqualTo((double) RETENTION_DAYS);
    }

    @Test
    void aSweepWithNothingPastTheWindowChangesNothingAndSaysSo() {
        createPartition(boundaryDay);
        createPartition(futureDay);

        RetentionResult result = retention.sweep(TYPE);

        assertThat(result.lockAcquired()).isTrue();
        assertThat(result.changedAnything()).isFalse();
        assertThat(partitionExists(boundaryDay)).isTrue();
        assertThat(partitionExists(futureDay)).isTrue();
    }

    // === Fixtures ==========================================================

    private static String partitionName(LocalDate day) {
        return PartitionNaming.partitionName(TYPE, day);
    }

    private static String bound(LocalDate day) {
        return PartitionNaming.partitionBound(day);
    }

    private void createPartition(LocalDate day) {
        jdbcTemplate.execute(
                "CREATE TABLE "
                        + partitionName(day)
                        + " PARTITION OF "
                        + TYPE.wireName()
                        + " "
                        + bound(day));
    }

    /** A minimal but real row, so the drop is observed through the parent table. */
    private void insertRow(LocalDate day, String uid) {
        jdbcTemplate.update(
                """
                INSERT INTO %s (
                    uid, tenant, maintenant, clsbox, ts_server, ts_device, schema_version,
                    rx_nr_pkts, rx_crc_missing, rx_crc_bad, tx_success, tx_failed, tx_peek,
                    tx_peek_index_zero, tx_override, tx_busy, tx_dequeue_error,
                    tx_type_beacon, chip_comm_error, util_fopen_failed
                ) VALUES (?, 'tenant-alpha', 'management-parent', 'A1B2C3D4E5F6', ?, ?, 1,
                          0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                """
                        .formatted(TYPE.wireName()),
                uid,
                java.sql.Timestamp.from(day.atTime(12, 0).toInstant(ZoneOffset.UTC)),
                java.sql.Timestamp.from(day.atTime(12, 0).toInstant(ZoneOffset.UTC)));
    }

    private List<String> uidsInParent() {
        return jdbcTemplate.queryForList(
                "SELECT uid FROM " + TYPE.wireName() + " WHERE tenant = 'tenant-alpha'",
                String.class);
    }

    private boolean partitionExists(LocalDate day) {
        Boolean attached =
                jdbcTemplate.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM pg_inherits i JOIN pg_class c "
                                + "ON c.oid = i.inhrelid WHERE i.inhparent = to_regclass(?) "
                                + "AND c.relname = ?)",
                        Boolean.class,
                        TYPE.wireName(),
                        partitionName(day));
        return Boolean.TRUE.equals(attached);
    }

    private boolean relationExists(String relationName) {
        return Boolean.TRUE.equals(
                jdbcTemplate.queryForObject(
                        "SELECT to_regclass(?) IS NOT NULL", Boolean.class, relationName));
    }

    private boolean detachIsPending(LocalDate day) {
        return Boolean.TRUE.equals(
                jdbcTemplate.queryForObject(
                        "SELECT COALESCE((SELECT i.inhdetachpending FROM pg_inherits i "
                                + "JOIN pg_class c ON c.oid = i.inhrelid "
                                + "WHERE i.inhparent = to_regclass(?) AND c.relname = ?), false)",
                        Boolean.class,
                        TYPE.wireName(),
                        partitionName(day)));
    }

    /**
     * Reproduces a killed maintenance run: hold a lock on the partition from one session so
     * {@code DETACH CONCURRENTLY} has to wait, then let a statement timeout cancel it. That
     * is what a rolling deploy or an OOM does to a real run.
     */
    private void leavePendingDetach(LocalDate day) throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Exception> holderFailures = new ArrayList<>();

        Thread holder =
                new Thread(
                        () -> {
                            try (Connection connection = dataSource.getConnection()) {
                                connection.setAutoCommit(false);
                                try (Statement statement = connection.createStatement()) {
                                    statement.execute(
                                            "SELECT count(*) FROM " + partitionName(day));
                                }
                                holding.countDown();
                                release.await(30, TimeUnit.SECONDS);
                                connection.rollback();
                            } catch (Exception failure) {
                                holderFailures.add(failure);
                                holding.countDown();
                            }
                        },
                        "detach-blocker");
        holder.start();
        assertThat(holding.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(holderFailures).isEmpty();

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            // Separate executes: several statements in one round trip become an implicit
            // transaction block, and DETACH CONCURRENTLY refuses to run in one.
            statement.execute("SET statement_timeout = '2s'");
            try {
                statement.execute(
                        "ALTER TABLE "
                                + TYPE.wireName()
                                + " DETACH PARTITION "
                                + partitionName(day)
                                + " CONCURRENTLY");
                throw new IllegalStateException(
                        "The detach was expected to be cancelled while a session held the "
                                + "partition, but it completed");
            } catch (SQLException cancelled) {
                // 57014 query_canceled — exactly the interruption this test is staging.
                assertThat(cancelled.getSQLState()).isEqualTo("57014");
            }
        } finally {
            release.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(15));
        }
    }

    private double counter(String name) {
        return registry.get(name).tag("type", TYPE.wireName()).counter().count();
    }

    private double gauge(String name) {
        return registry.get(name).tag("type", TYPE.wireName()).gauge().value();
    }
}
