package com.zenner.datahub.stats.writer.retention;

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

class PartitionPrecreationSchedulerTest {

    private static final RetentionProperties PROPERTIES =
            new RetentionProperties(
                    RetentionProperties.PartitionGranularity.DAILY, 7, "0 30 3 * * *");

    @Test
    void selectedProductionDeploymentMaintainsOnlyItsConfiguredStatType() {
        PartitionPrecreator precreator = mock(PartitionPrecreator.class);
        when(precreator.ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(7)))
                .thenReturn(new PartitionPrecreationResult(true, 8));
        PartitionPrecreationScheduler scheduler =
                new PartitionPrecreationScheduler(precreator, PROPERTIES, "lorastats");

        scheduler.preCreatePartitions();

        verify(precreator).ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(7));
        verify(precreator, never()).existingParentTypes();
    }

    @Test
    void allDevelopmentModeDiscoversTablesWhoseMigrationsHaveLanded() {
        PartitionPrecreator precreator = mock(PartitionPrecreator.class);
        when(precreator.existingParentTypes()).thenReturn(List.of(StatType.LORASTATS));
        when(precreator.ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(7)))
                .thenReturn(new PartitionPrecreationResult(false, 0));
        PartitionPrecreationScheduler scheduler =
                new PartitionPrecreationScheduler(precreator, PROPERTIES, "all");

        scheduler.preCreatePartitions();

        verify(precreator).existingParentTypes();
        verify(precreator).ensurePartitions(eq(StatType.LORASTATS), any(LocalDate.class), eq(7));
    }

    @Test
    void rejectsBlankUnknownAndCaseChangedDeploymentValues() {
        PartitionPrecreator precreator = mock(PartitionPrecreator.class);

        assertThatThrownBy(() -> new PartitionPrecreationScheduler(precreator, PROPERTIES, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(
                        () ->
                                new PartitionPrecreationScheduler(
                                        precreator, PROPERTIES, "futurestats"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown stat type");
        assertThatThrownBy(
                        () ->
                                new PartitionPrecreationScheduler(
                                        precreator, PROPERTIES, "LORASTATS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown stat type");
    }
}
