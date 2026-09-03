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
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PartitionMaintenanceTest {

    private static final RetentionProperties PROPERTIES =
            new RetentionProperties(
                    RetentionProperties.PartitionGranularity.DAILY, 7, "0 30 3 * * *");

    private final PartitionPrecreator precreator = mock(PartitionPrecreator.class);

    @Test
    void selectedProductionDeploymentMaintainsOnlyItsConfiguredStatType() {
        when(precreator.ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(7)))
                .thenReturn(new PartitionPrecreationResult(true, 8));

        maintenance("lorastats").ensureOnSchedule();

        verify(precreator).ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(7));
        verify(precreator, never()).existingParentTypes();
    }

    @Test
    void allDevelopmentModeDiscoversTablesWhoseMigrationsHaveLanded() {
        when(precreator.existingParentTypes()).thenReturn(List.of(StatType.LORASTATS));
        when(precreator.ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(7)))
                .thenReturn(new PartitionPrecreationResult(false, 0));

        maintenance("all").ensureOnSchedule();

        verify(precreator).existingParentTypes();
        verify(precreator).ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(7));
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
        when(precreator.ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(7)))
                .thenThrow(new IllegalStateException("tablespace is full"));
        when(precreator.ensurePartitions(eq(StatType.SYSSTATS), any(LocalDate.class), eq(7)))
                .thenReturn(new PartitionPrecreationResult(true, 8));
        when(precreator.ensurePartitions(eq(StatType.NETSTATS), any(LocalDate.class), eq(7)))
                .thenReturn(new PartitionPrecreationResult(true, 8));

        maintenance("all").ensureOnSchedule();

        verify(precreator).ensurePartitions(eq(StatType.SYSSTATS), any(LocalDate.class), eq(7));
        verify(precreator).ensurePartitions(eq(StatType.NETSTATS), any(LocalDate.class), eq(7));
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
        when(precreator.ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(7)))
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
        when(precreator.ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(7)))
                .thenReturn(PartitionPrecreationResult.lockNotAcquired());

        maintenance("lorastats").ensureBeforeIngestion();

        verify(precreator).ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(7));
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

    private PartitionMaintenance maintenance(String configuredStatType) {
        return new PartitionMaintenance(precreator, PROPERTIES, configuredStatType);
    }
}
