package com.zenner.datahub.stats.writer.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class PartitionMaintenanceSchedulerTest {

    private final PartitionMaintenance maintenance = mock(PartitionMaintenance.class);

    @Test
    void theCronRunUsesTheScheduledFailurePolicy() {
        new PartitionMaintenanceScheduler(maintenance).maintainPartitions();

        // maintainOnSchedule, never ensureBeforeIngestion: a cron failure in an already
        // serving process must not take down the other stat types.
        verify(maintenance).maintainOnSchedule();
        verifyNoMoreInteractions(maintenance);
    }

    @Test
    void reusesTheExistingRetentionScheduleInUtcRatherThanASecondProperty() throws Exception {
        Method scheduled = PartitionMaintenanceScheduler.class.getMethod("maintainPartitions");
        Scheduled annotation = scheduled.getAnnotation(Scheduled.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.cron()).isEqualTo("${stats.retention.schedule}");
        // A local-time cron silently moves the maintenance window twice a year.
        assertThat(annotation.zone()).isEqualTo("UTC");
    }

    /**
     * Pre-creation and retention share one scheduled entry point on purpose. Two
     * {@code @Scheduled} methods on the same cron would run in whatever order Spring
     * registered them, and the order is load-bearing: tomorrow's partition is created
     * before yesterday's is dropped.
     */
    @Test
    void thereIsExactlyOneScheduledMaintenanceEntryPointInThisPackage() {
        long scheduledMethods =
                java.util.stream.Stream.of(
                                PartitionMaintenanceScheduler.class.getDeclaredMethods())
                        .filter(method -> method.isAnnotationPresent(Scheduled.class))
                        .count();

        assertThat(scheduledMethods).isEqualTo(1);
    }
}
