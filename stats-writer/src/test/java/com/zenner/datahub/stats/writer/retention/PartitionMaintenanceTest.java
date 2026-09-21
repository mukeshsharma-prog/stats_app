package com.zenner.datahub.stats.writer.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.writer.retention.PartitionPrecreator.PartitionPrecreationResult;
import com.zenner.datahub.stats.writer.retention.PartitionRetention.RetentionResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PartitionMaintenanceTest {

    private static final RetentionProperties PROPERTIES = properties(Duration.ZERO);

    /**
     * DL-149: every pass ensures the whole retained window behind today AND the horizon
     * ahead of it — 90 retention days + 7 pre-create days here — counted from the cutoff.
     */
    private static final int WINDOW_AND_HORIZON = 90 + 7;

    private final PartitionPrecreator precreator = mock(PartitionPrecreator.class);
    private final PartitionRetention retention = mock(PartitionRetention.class);
    private final RetentionMetrics metrics = new RetentionMetrics(new SimpleMeterRegistry());

    private static RetentionProperties properties(Duration stagger) {
        return new RetentionProperties(
                RetentionProperties.PartitionGranularity.DAILY,
                7,
                "0 30 3 * * *",
                90,
                Map.of(),
                stagger);
    }

    private static RetentionResult swept() {
        return new RetentionResult(true, 1, 0, 0, LocalDate.of(2026, 6, 12));
    }

    @Test
    void selectedProductionDeploymentMaintainsOnlyItsConfiguredStatType() {
        when(precreator.ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(WINDOW_AND_HORIZON)))
                .thenReturn(new PartitionPrecreationResult(true, 8));
        when(retention.sweep(StatType.LORASTATS)).thenReturn(swept());

        maintenance("lorastats").maintainOnSchedule();

        verify(precreator).ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(WINDOW_AND_HORIZON));
        verify(retention).sweep(StatType.LORASTATS);
        verify(precreator, never()).existingParentTypes();
    }

    @Test
    void allDevelopmentModeDiscoversTablesWhoseMigrationsHaveLanded() {
        when(precreator.existingParentTypes()).thenReturn(List.of(StatType.LORASTATS));
        when(precreator.ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(WINDOW_AND_HORIZON)))
                .thenReturn(new PartitionPrecreationResult(false, 0));
        when(retention.sweep(StatType.LORASTATS)).thenReturn(RetentionResult.lockNotAcquired());

        maintenance("all").maintainOnSchedule();

        verify(precreator).existingParentTypes();
        verify(precreator).ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(WINDOW_AND_HORIZON));
        verify(retention).sweep(StatType.LORASTATS);
    }

    @Test
    void rejectsBlankUnknownAndCaseChangedDeploymentValues() {
        assertThatThrownBy(() -> maintenance(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> maintenance("futurestats"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown stat type");
        assertThatThrownBy(() -> maintenance("LORASTATS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown stat type");
    }

    /**
     * One failing stat type must not leave the other four without partitions
     * tomorrow. This is the whole reason the scheduled path swallows and logs rather
     * than propagating: the loop is the only thing standing between the remaining
     * types and a stopped ingest.
     */
    @Test
    void oneFailingTypeOnTheScheduleDoesNotStarveTheOthers() {
        when(precreator.existingParentTypes())
                .thenReturn(List.of(StatType.LORASTATS, StatType.SYSSTATS, StatType.NETSTATS));
        when(precreator.ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(WINDOW_AND_HORIZON)))
                .thenThrow(new IllegalStateException("tablespace is full"));
        when(precreator.ensurePartitions(eq(StatType.SYSSTATS), any(LocalDate.class), eq(WINDOW_AND_HORIZON)))
                .thenReturn(new PartitionPrecreationResult(true, 8));
        when(precreator.ensurePartitions(eq(StatType.NETSTATS), any(LocalDate.class), eq(WINDOW_AND_HORIZON)))
                .thenReturn(new PartitionPrecreationResult(true, 8));
        when(retention.sweep(any(StatType.class))).thenReturn(swept());

        maintenance("all").maintainOnSchedule();

        verify(precreator).ensurePartitions(eq(StatType.SYSSTATS), any(LocalDate.class), eq(WINDOW_AND_HORIZON));
        verify(precreator).ensurePartitions(eq(StatType.NETSTATS), any(LocalDate.class), eq(WINDOW_AND_HORIZON));
    }

    /**
     * Retention is isolated from pre-creation as well as from the other types, and the
     * direction matters: a type that cannot reclaim yesterday must still get tomorrow's
     * partition, or a storage problem turns into an ingestion outage.
     */
    @Test
    void aFailingRetentionSweepDoesNotStopPrecreationForTheOtherTypes() {
        when(precreator.existingParentTypes())
                .thenReturn(List.of(StatType.LORASTATS, StatType.SYSSTATS));
        when(precreator.ensurePartitions(any(StatType.class), any(LocalDate.class), eq(WINDOW_AND_HORIZON)))
                .thenReturn(new PartitionPrecreationResult(true, 8));
        when(retention.sweep(StatType.LORASTATS))
                .thenThrow(new IllegalStateException("detach is wedged"));
        when(retention.sweep(StatType.SYSSTATS)).thenReturn(swept());

        maintenance("all").maintainOnSchedule();

        verify(precreator).ensurePartitions(eq(StatType.SYSSTATS), any(LocalDate.class), eq(WINDOW_AND_HORIZON));
        verify(retention).sweep(StatType.SYSSTATS);
    }

    /**
     * Tomorrow's partition is created before yesterday's is dropped. Two separately
     * scheduled tasks on one cron would have left this to registration order.
     */
    @Test
    void precreationRunsBeforeRetentionForEachType() {
        when(precreator.ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(WINDOW_AND_HORIZON)))
                .thenReturn(new PartitionPrecreationResult(true, 8));
        when(retention.sweep(StatType.LORASTATS)).thenReturn(swept());

        maintenance("lorastats").maintainOnSchedule();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(precreator, retention);
        order.verify(precreator)
                .ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(WINDOW_AND_HORIZON));
        order.verify(retention).sweep(StatType.LORASTATS);
    }

    /** ADR-003 D3 — the five types are staggered rather than fired together. */
    @Test
    void staggersTheTypesRatherThanFiringThemTogether() {
        Duration stagger = Duration.ofMillis(60);
        when(precreator.existingParentTypes())
                .thenReturn(List.of(StatType.LORASTATS, StatType.LTESTATS, StatType.SYSSTATS));
        when(precreator.ensurePartitions(any(StatType.class), any(LocalDate.class), eq(WINDOW_AND_HORIZON)))
                .thenReturn(new PartitionPrecreationResult(true, 8));
        when(retention.sweep(any(StatType.class))).thenReturn(swept());

        long startedAt = System.nanoTime();
        new PartitionMaintenance(
                        precreator,
                        retention,
                        properties(stagger),
                        new RetentionWindow(properties(stagger), Clock.systemUTC()),
                        metrics,
                        Clock.systemUTC(),
                        "all")
                .maintainOnSchedule();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        // Offsets are 0, 1x and 2x the stagger, measured from the start of the run, so a
        // three-type pass cannot finish faster than two intervals.
        assertThat(elapsed)
                .as("the offsets are derived from the stat type, not from the loop position")
                .isGreaterThanOrEqualTo(stagger.multipliedBy(2));
        verify(retention).sweep(StatType.SYSSTATS);
    }

    /**
     * At startup the same failure is fatal, because the alternative is a module that
     * consumes into a table with no partition: every insert fails, the T-020 contract
     * correctly withholds the ACK, and the consumer wedges once its prefetch window is
     * full. Refusing to start keeps the records in the broker and makes the cause
     * obvious (ADR-003 D1 — the failure must be loud).
     */
    @Test
    void startupRefusesToComeUpWhenPartitionsCannotBeCreated() {
        when(precreator.parentTableExists(StatType.LORASTATS)).thenReturn(true);
        when(precreator.ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(WINDOW_AND_HORIZON)))
                .thenThrow(new IllegalStateException("tablespace is full"));

        assertThatThrownBy(() -> maintenance("lorastats").ensureBeforeIngestion())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tablespace is full");
    }

    @Test
    void startupIsSatisfiedWhenAnotherReplicaAlreadyHoldsTheAdvisoryLock() {
        when(precreator.parentTableExists(StatType.LORASTATS)).thenReturn(true);
        // ADR-003 D3: losing the lock race is the normal multi-replica case, not a
        // failure. The winning replica creates the partitions this one also needs.
        when(precreator.ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(WINDOW_AND_HORIZON)))
                .thenReturn(PartitionPrecreationResult.lockNotAcquired());

        maintenance("lorastats").ensureBeforeIngestion();

        verify(precreator).ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(WINDOW_AND_HORIZON));
    }

    /**
     * Startup never sweeps. A process coming up should start ingesting, not start
     * dropping tables — D4 puts retention on a once-daily off-peak cron, and a restart
     * loop would otherwise turn every crash into a retention run.
     */
    @Test
    void startupDoesNotRunRetention() {
        when(precreator.parentTableExists(StatType.LORASTATS)).thenReturn(true);
        when(precreator.ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(WINDOW_AND_HORIZON)))
                .thenReturn(new PartitionPrecreationResult(true, 8));

        maintenance("lorastats").ensureBeforeIngestion();

        verify(retention, never()).sweep(any(StatType.class));
    }

    /**
     * The counters have to export at zero from startup. "Has anything expired on
     * arrival?" must be answerable as zero rather than as a missing series.
     */
    @Test
    void registersThisDeploymentsRetentionCountersBeforeIngestionStarts() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(precreator.parentTableExists(StatType.LORASTATS)).thenReturn(true);
        when(precreator.ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(WINDOW_AND_HORIZON)))
                .thenReturn(new PartitionPrecreationResult(true, 8));

        new PartitionMaintenance(
                        precreator,
                        retention,
                        PROPERTIES,
                        new RetentionWindow(PROPERTIES, Clock.systemUTC()),
                        new RetentionMetrics(registry),
                        Clock.systemUTC(),
                        "lorastats")
                .ensureBeforeIngestion();

        assertThat(
                        registry.find(RetentionMetrics.EXPIRED_ON_ARRIVAL)
                                .tag("type", StatType.LORASTATS.wireName())
                                .counter())
                .isNotNull()
                .extracting(counter -> counter.count())
                .isEqualTo(0.0d);
    }

    @Test
    void targetTypesIsTheSingleConfiguredTypeInProduction() {
        assertThat(maintenance("netstats").targetTypes()).containsExactly(StatType.NETSTATS);
    }

    /**
     * A writer configured for a stat type whose migration has not landed cannot work
     * at all. Naming that at startup beats letting it surface as a DDL error deep
     * inside pre-creation, or worse, as failing inserts after the listener starts.
     */
    @Test
    void startupNamesAConfiguredStatTypeThatHasNoTable() {
        when(precreator.parentTableExists(StatType.NETSTATS)).thenReturn(false);

        assertThatThrownBy(() -> maintenance("netstats").ensureBeforeIngestion())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no partitioned table of that name exists");
    }

    /** The maintenance day is UTC, not the host's zone — partition bounds are UTC. */
    @Test
    void resolvesTodayInUtcRatherThanTheHostZone() {
        Instant middayUtc = Instant.parse("2026-09-10T12:00:00Z");
        when(precreator.ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(WINDOW_AND_HORIZON)))
                .thenReturn(new PartitionPrecreationResult(true, 8));
        when(retention.sweep(StatType.LORASTATS)).thenReturn(swept());

        // A zone 12 hours ahead: local date is already the 11th.
        Clock hostClock = Clock.fixed(middayUtc, ZoneOffset.ofHours(12));
        new PartitionMaintenance(
                        precreator,
                        retention,
                        PROPERTIES,
                        new RetentionWindow(PROPERTIES, hostClock),
                        metrics,
                        hostClock,
                        "lorastats")
                .maintainOnSchedule();

        // 10 Sep 2026 UTC minus 90 days is the cutoff; the ensured range starts there and
        // runs 97 days, to 17 Sep — today plus the seven-day horizon (DL-149).
        verify(precreator)
                .ensurePartitions(StatType.LORASTATS, LocalDate.of(2026, 6, 12), WINDOW_AND_HORIZON);
    }

    private PartitionMaintenance maintenance(String configuredStatType) {
        return new PartitionMaintenance(
                precreator,
                retention,
                PROPERTIES,
                new RetentionWindow(PROPERTIES, Clock.systemUTC()),
                metrics,
                Clock.systemUTC(),
                configuredStatType);
    }
}
