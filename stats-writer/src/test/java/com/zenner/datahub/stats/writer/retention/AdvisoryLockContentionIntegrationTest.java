package com.zenner.datahub.stats.writer.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariDataSource;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.testsupport.PostgresSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * T-054 — advisory-lock contention between two Writer <em>replicas</em> (ADR-003 D3;
 * STA-23C).
 *
 * <p>{@code PartitionRetentionIntegrationTest.skipsTheRunEntirelyWhenAnotherReplicaHoldsTheAdvisoryLock}
 * proves a replica that loses the race changes nothing — but it holds the lock from a
 * second connection of the <em>same</em> pool. The lock is session-level, so the unit that
 * matters is the pool, not the JVM: two pools are two replicas as far as PostgreSQL can
 * tell. This test builds two complete maintenance stacks — each its own
 * {@link HikariDataSource}, {@link PartitionMaintenance}, metrics registry and clock —
 * against one database, and fires both.</p>
 *
 * <p>Why it is not academic: ADR-003 D8 runs N replicas <em>per type</em>, so two replicas
 * firing the same cron at the same second is the production default, not an edge.</p>
 *
 * <p>What it asserts beyond "one sweep ran": that the losing replica logs at DEBUG and not
 * ERROR, that no partition is dropped twice or not at all across twenty simultaneous
 * rounds, and — the failure to design for — that <strong>the pools are clean
 * afterwards</strong>. The lock is session-level and the connection is pooled, so a lock
 * leaked on a failure path outlives the borrow and blocks that type's maintenance on every
 * replica until the physical connection is evicted. {@link PartitionSession} releases
 * unconditionally, including when the work throws, and restores the session time zone and
 * auto-commit it changed; this test borrows every pooled connection afterwards and checks.</p>
 *
 * <p>The literal two-<em>process</em> run against the Compose stack is
 * {@code ops/drills/t054-advisory-lock-contention.sh}; this is the deterministic, in-build
 * half.</p>
 */
@SpringBootTest(
        classes = com.zenner.datahub.stats.writer.StatsWriterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            // The Spring context only applies the migrations and lends its container; the
            // two replicas below are built by hand so each owns its own pool.
            "stats.writer.stat-type=netstats",
            "stats.retention.schedule=-",
            "stats.retention.pre-create-on-startup=false"
        })
@Import(PostgresSupport.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdvisoryLockContentionIntegrationTest {

    private static final StatType TYPE = StatType.LORASTATS;
    private static final int RETENTION_DAYS = 90;
    private static final int POOL_SIZE = 3;
    private static final int ROUNDS = 20;

    private Replica a;
    private Replica b;
    private String serverTimeZone;
    private ListAppender<ILoggingEvent> maintenanceLog;
    private Level previousLevel;

    /** One Writer replica as the lock sees it: its own pool, and everything that borrows from it. */
    private static final class Replica implements AutoCloseable {
        final String name;
        final HikariDataSource pool;
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final RetentionMetrics metrics = new RetentionMetrics(registry);
        final RetentionWindow window;
        final PartitionPrecreator precreator;
        final PartitionRetention retention;
        final PartitionMaintenance maintenance;
        final PartitionSession session;

        Replica(String name, PostgreSQLContainer<?> container) {
            this.name = name;
            pool = new HikariDataSource();
            pool.setPoolName(name);
            pool.setJdbcUrl(container.getJdbcUrl());
            pool.setUsername(container.getUsername());
            pool.setPassword(container.getPassword());
            pool.setMaximumPoolSize(POOL_SIZE);
            pool.setMinimumIdle(POOL_SIZE);
            // So pg_stat_activity can attribute every backend to its replica.
            pool.addDataSourceProperty("ApplicationName", name);
            RetentionProperties properties =
                    new RetentionProperties(
                            RetentionProperties.PartitionGranularity.DAILY,
                            7,
                            "-",
                            RETENTION_DAYS,
                            Map.of(),
                            Duration.ZERO);
            Clock clock = Clock.systemUTC();
            window = new RetentionWindow(properties, clock);
            precreator = new PartitionPrecreator(pool);
            retention = new PartitionRetention(pool, window, metrics);
            maintenance =
                    new PartitionMaintenance(
                            precreator, retention, properties, window, metrics, clock, TYPE.wireName());
            session = new PartitionSession(pool);
            metrics.registerFor(TYPE);
        }

        double dropped() {
            return registry.get(RetentionMetrics.PARTITIONS_DROPPED).tag("type", TYPE.wireName()).counter().count();
        }

        @Override
        public void close() {
            pool.close();
        }
    }

    @BeforeAll
    void buildTwoReplicasAgainstOneDatabase() throws SQLException {
        a = new Replica("replica-a", PostgresSupport.container());
        b = new Replica("replica-b", PostgresSupport.container());
        try (Connection probe = a.pool.getConnection()) {
            serverTimeZone = show(probe, "TimeZone");
        }
        Logger logger = (Logger) LoggerFactory.getLogger(PartitionMaintenance.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        maintenanceLog = new ListAppender<>();
        maintenanceLog.start();
        logger.addAppender(maintenanceLog);
    }

    @AfterAll
    void closeReplicas() {
        Logger logger = (Logger) LoggerFactory.getLogger(PartitionMaintenance.class);
        logger.detachAppender(maintenanceLog);
        logger.setLevel(previousLevel);
        a.close();
        b.close();
    }

    @BeforeEach
    void startClean() throws SQLException {
        maintenanceLog.list.clear();
        dropTestPartitions();
    }

    @AfterEach
    void leaveNothingBehind() throws SQLException {
        dropTestPartitions();
    }

    // === 1. Deterministic overlap: B fires while A is mid-maintenance =================

    @Test
    void aReplicaFiringWhileAnotherIsMidMaintenanceSkipsBothHalvesAndChangesNothing() throws Exception {
        LocalDate droppable = a.window.cutoffDay(TYPE).minusDays(1);
        createPartition(droppable);
        CountDownLatch aHoldsTheLock = new CountDownLatch(1);
        CountDownLatch bHasFinished = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Replica A is inside its maintenance window: lock held on one of ITS sessions.
            Future<?> aRun = pool.submit(() -> a.session.withLock(TYPE, "hold for the drill", connection -> {
                aHoldsTheLock.countDown();
                try {
                    assertThat(bHasFinished.await(30, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while holding the lock", interrupted);
                }
                return Boolean.TRUE;
            }));
            assertThat(aHoldsTheLock.await(10, TimeUnit.SECONDS)).isTrue();

            // Replica B's cron fires now.
            b.maintenance.maintainOnSchedule();
            bHasFinished.countDown();
            aRun.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(partitionExists(droppable)).as("B dropped nothing while A held the lock").isTrue();
        assertThat(b.dropped()).isZero();
        assertThat(events("partition_precreation_skipped")).hasSize(1).allMatch(e -> e.getLevel() == Level.DEBUG);
        assertThat(events("retention_skipped")).hasSize(1).allMatch(e -> e.getLevel() == Level.DEBUG);
        assertThat(maintenanceLog.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN));

        // A has released; B's next run does the work.
        b.maintenance.maintainOnSchedule();
        assertThat(partitionExists(droppable)).isFalse();
        assertThat(b.dropped()).isEqualTo(1.0d);
        assertThat(events("retention_applied")).hasSize(1);
    }

    // === 2. Both firing the cron together, twenty times ===============================

    @Test
    void twentySimultaneousRoundsDropEachPartitionExactlyOnceAndNeverError() throws Exception {
        LocalDate droppable = a.window.cutoffDay(TYPE).minusDays(1);
        CyclicBarrier bothFire = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int overlaps = 0;
        double droppedBefore = a.dropped() + b.dropped();
        try {
            for (int round = 0; round < ROUNDS; round++) {
                createPartition(droppable);
                int skippedBefore = events("retention_skipped").size();
                List<Future<?>> runs = new ArrayList<>();
                for (Replica replica : List.of(a, b)) {
                    runs.add(pool.submit(() -> {
                        bothFire.await(10, TimeUnit.SECONDS);
                        replica.maintenance.maintainOnSchedule();
                        return null;
                    }));
                }
                for (Future<?> run : runs) {
                    run.get(60, TimeUnit.SECONDS);
                }
                assertThat(partitionExists(droppable)).as("round %d: dropped by exactly one replica", round).isFalse();
                if (events("retention_skipped").size() > skippedBefore) {
                    overlaps++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(a.dropped() + b.dropped() - droppedBefore)
                .as("every round's partition was dropped once, never twice")
                .isEqualTo(ROUNDS);
        assertThat(events("retention_failed")).isEmpty();
        assertThat(events("partition_precreation_failed")).isEmpty();
        assertThat(maintenanceLog.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN));
        System.out.printf(
                "[T-054] %d simultaneous rounds: dropped by A=%.0f B=%.0f, rounds where the loser had to skip=%d, errors=0%n",
                ROUNDS, a.dropped(), b.dropped(), overlaps);
    }

    // === 3. The failure path releases the lock and restores the session ===============

    @Test
    void aReplicaWhoseMaintenanceThrowsLeavesNoLockAndNoAlteredSessionBehind() throws Exception {
        IllegalStateException boom = new IllegalStateException("DDL failed mid-run");
        assertThatThrownBy(() -> a.session.withLock(TYPE, "fail on purpose", connection -> {
            assertThat(show(connection, "TimeZone")).isEqualTo("UTC");
            throw boom;
        })).isSameAs(boom);

        assertThat(advisoryLocksHeld()).as("the lock did not outlive the failed borrow").isZero();
        // The other replica can run immediately; nothing is wedged.
        b.maintenance.maintainOnSchedule();
        assertThat(events("retention_skipped")).isEmpty();
        assertThat(events("partition_precreation_skipped")).isEmpty();
    }

    // === 4. Every pooled connection of both replicas is clean =========================

    @Test
    void everyPooledConnectionOfBothReplicasIsCleanAfterContention() throws Exception {
        LocalDate droppable = a.window.cutoffDay(TYPE).minusDays(1);
        createPartition(droppable);
        a.maintenance.maintainOnSchedule();
        b.maintenance.maintainOnSchedule();

        assertThat(advisoryLocksHeld()).isZero();
        for (Replica replica : List.of(a, b)) {
            List<Connection> borrowed = new ArrayList<>();
            try {
                // Borrow the WHOLE pool so every physical session is inspected, not the
                // one that happens to come back first.
                for (int i = 0; i < POOL_SIZE; i++) {
                    borrowed.add(replica.pool.getConnection());
                }
                for (Connection connection : borrowed) {
                    assertThat(show(connection, "TimeZone"))
                            .as("%s: session time zone restored", replica.name)
                            .isEqualTo(serverTimeZone);
                    assertThat(connection.getAutoCommit())
                            .as("%s: auto-commit as the pool hands it out", replica.name)
                            .isTrue();
                }
            } finally {
                for (Connection connection : borrowed) {
                    connection.close();
                }
            }
            // Asked of the server once every session is back in the pool: a backend the
            // maintenance left inside a transaction would still be there.
            assertThat(idleInTransaction(replica.name))
                    .as("%s: no backend left idle in transaction", replica.name)
                    .isZero();
        }
    }

    // ---- helpers -----------------------------------------------------------

    private List<ILoggingEvent> events(String event) {
        List<ILoggingEvent> matching = new ArrayList<>();
        for (ILoggingEvent e : List.copyOf(maintenanceLog.list)) {
            if (e.getKeyValuePairs() != null
                    && e.getKeyValuePairs().stream().anyMatch(kv -> "event".equals(kv.key) && event.equals(kv.value))) {
                matching.add(e);
            }
        }
        return matching;
    }

    private void createPartition(LocalDate day) throws SQLException {
        execute("CREATE TABLE IF NOT EXISTS " + PartitionNaming.partitionName(TYPE, day)
                + " PARTITION OF lorastats " + PartitionNaming.partitionBound(day));
    }

    private boolean partitionExists(LocalDate day) throws SQLException {
        return query("SELECT to_regclass('" + PartitionNaming.partitionName(TYPE, day) + "') IS NOT NULL")
                .equals("t");
    }

    private void dropTestPartitions() throws SQLException {
        execute("DROP TABLE IF EXISTS " + PartitionNaming.partitionName(TYPE, a.window.cutoffDay(TYPE).minusDays(1)));
    }

    private int advisoryLocksHeld() throws SQLException {
        return Integer.parseInt(query("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'"));
    }

    /** Backends of one replica's pool the server sees as idle inside an open transaction. */
    private int idleInTransaction(String replica) throws SQLException {
        return Integer.parseInt(query(
                "SELECT count(*) FROM pg_stat_activity WHERE application_name = '" + replica
                        + "' AND state LIKE 'idle in transaction%'"));
    }

    private static String show(Connection connection, String setting) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SHOW " + setting)) {
            result.next();
            return result.getString(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = a.pool.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String query(String sql) throws SQLException {
        try (Connection connection = a.pool.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
