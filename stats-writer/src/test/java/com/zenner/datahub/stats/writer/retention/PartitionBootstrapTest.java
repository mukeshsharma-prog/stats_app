package com.zenner.datahub.stats.writer.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

class PartitionBootstrapTest {

    private final PartitionMaintenance maintenance = mock(PartitionMaintenance.class);

    @Test
    void ensuresPartitionsBeforeIngestionAndReportsRunning() {
        PartitionBootstrap bootstrap = new PartitionBootstrap(maintenance);
        assertThat(bootstrap.isRunning()).isFalse();

        bootstrap.start();

        verify(maintenance).ensureBeforeIngestion();
        assertThat(bootstrap.isRunning()).isTrue();
    }

    @Test
    void failureIsFatalSoTheModuleNeverConsumesIntoAnUnpartitionedTable() {
        doThrow(new IllegalStateException("no such tablespace"))
                .when(maintenance)
                .ensureBeforeIngestion();
        PartitionBootstrap bootstrap = new PartitionBootstrap(maintenance);

        assertThatThrownBy(bootstrap::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no such tablespace");
        assertThat(bootstrap.isRunning()).isFalse();
    }

    /**
     * The ordering guarantee this class exists for, asserted against the real Spring
     * AMQP container rather than a remembered constant. SmartLifecycle starts lower
     * phases first, so partitions are created before any listener pulls a delivery.
     * If Spring AMQP ever moves its default phase below {@code PHASE}, this fails
     * here instead of failing as an unexplained wedge in production.
     */
    @Test
    void startsBeforeTheRabbitListenerContainers() {
        AbstractMessageListenerContainer container =
                new SimpleMessageListenerContainer(mock(ConnectionFactory.class));

        assertThat(PartitionBootstrap.PHASE).isLessThan(container.getPhase());
    }
}
