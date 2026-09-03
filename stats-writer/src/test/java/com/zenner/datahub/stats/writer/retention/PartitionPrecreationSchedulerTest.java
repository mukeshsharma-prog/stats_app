package com.zenner.datahub.stats.writer.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class PartitionPrecreationSchedulerTest {

    private final PartitionMaintenance maintenance = mock(PartitionMaintenance.class);

    @Test
    void theCronRunUsesTheScheduledFailurePolicy() {
        new PartitionPrecreationScheduler(maintenance).preCreatePartitions();

        // ensureOnSchedule, never ensureBeforeIngestion: a cron failure in an already
        // serving process must not take down the other stat types.
        verify(maintenance).ensureOnSchedule();
        verifyNoMoreInteractions(maintenance);
    }

    @Test
    void reusesTheExistingRetentionScheduleInUtcRatherThanASecondProperty() throws Exception {
        Method scheduled = PartitionPrecreationScheduler.class.getMethod("preCreatePartitions");
        Scheduled annotation = scheduled.getAnnotation(Scheduled.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.cron()).isEqualTo("${stats.retention.schedule}");
        // A local-time cron silently moves the maintenance window twice a year.
        assertThat(annotation.zone()).isEqualTo("UTC");
    }
}
