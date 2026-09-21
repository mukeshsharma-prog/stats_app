package com.zenner.datahub.stats.writer.retention;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zenner.datahub.stats.common.model.StatType;
import java.sql.SQLException;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class PartitionPrecreatorFailureTest {

    @Test
    void reportsConnectionFailureAsALoudMaintenanceError() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("database unavailable"));

        PartitionPrecreator precreator = new PartitionPrecreator(dataSource);

        assertThatThrownBy(
                        () ->
                                precreator.ensurePartitions(
                                        StatType.LORASTATS, LocalDate.of(2098, 8, 1), 7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pre-create partitions for lorastats")
                .hasRootCauseMessage("database unavailable");
    }

    @Test
    void reportsParentDiscoveryConnectionFailureAsALoudMaintenanceError() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("database unavailable"));

        assertThatThrownBy(() -> new PartitionPrecreator(dataSource).existingParentTypes())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("discover partitioned parent tables")
                .hasRootCauseMessage("database unavailable");
    }

    @Test
    void rejectsDateOverflowBeforeOpeningAConnection() {
        DataSource dataSource = mock(DataSource.class);
        PartitionPrecreator precreator = new PartitionPrecreator(dataSource);

        assertThatThrownBy(
                        () -> precreator.ensurePartitions(StatType.LORASTATS, LocalDate.MAX, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds LocalDate limits");
        verifyNoInteractions(dataSource);
    }

    @Test
    void rejectsAnExcessiveHorizonBeforeOpeningAConnection() {
        DataSource dataSource = mock(DataSource.class);
        PartitionPrecreator precreator = new PartitionPrecreator(dataSource);

        assertThatThrownBy(
                        () ->
                                precreator.ensurePartitions(
                                        StatType.LORASTATS,
                                        LocalDate.of(2098, 7, 1),
                                        RetentionProperties.MAX_ENSURED_DAYS + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed");
        verifyNoInteractions(dataSource);
    }

    @Test
    void rejectsNullArgumentsAndNegativeHorizonsBeforeOpeningAConnection() {
        DataSource dataSource = mock(DataSource.class);
        PartitionPrecreator precreator = new PartitionPrecreator(dataSource);

        assertThatThrownBy(
                        () ->
                                precreator.ensurePartitions(
                                        null, LocalDate.of(2098, 7, 1), 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () -> precreator.ensurePartitions(StatType.LORASTATS, null, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                precreator.ensurePartitions(
                                        StatType.LORASTATS, LocalDate.of(2098, 7, 1), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero or greater");
        verifyNoInteractions(dataSource);
    }

    @Test
    void enforcesPartitionPrecreationResultInvariants() {
        assertThatThrownBy(() -> new PartitionPrecreator.PartitionPrecreationResult(false, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PartitionPrecreator.PartitionPrecreationResult(true, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
