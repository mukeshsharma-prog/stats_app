package com.zenner.datahub.stats.writer.consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.writer.metrics.QueueAgeMetrics;
import com.zenner.datahub.stats.writer.retry.FailedDeliveryRouter;
import com.zenner.datahub.stats.writer.retry.UnconfirmedHandoffException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class LtestatsConsumerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final QueueAgeMetrics queueAge = new QueueAgeMetrics(registry, Clock.systemUTC());
    private final StatsMessageProcessor processor = mock(StatsMessageProcessor.class);
    private final StatsMessageProcessorAvailability availability =
            mock(StatsMessageProcessorAvailability.class);
    private final FailedDeliveryRouter router = mock(FailedDeliveryRouter.class);

    private LtestatsConsumer consumer() {
        when(availability.requiredProcessor(StatType.LTESTATS)).thenReturn(processor);
        return new LtestatsConsumer(availability, router, registry, queueAge);
    }

    private void assertAttemptMetered() {
        assertThat(registry.get("records_consumed").tag("type", "ltestats").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("records_processing_duration").tag("type", "ltestats").timer().count())
                .isEqualTo(1);
    }

    @Test
    void acknowledgesOnlyAfterTheProcessorReturnsSuccessfully() throws Exception {
        Channel channel = mock(Channel.class);
        Message message = delivery(42L);

        consumer().consume(message, channel);

        InOrder order = inOrder(processor, channel);
        order.verify(processor).process(StatType.LTESTATS, message);
        order.verify(channel).basicAck(42L, false);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(router);
        assertAttemptMetered();
    }

    @Test
    void routesAFailedDeliveryOnwardBeforeAcknowledgingIt() throws Exception {
        Channel channel = mock(Channel.class);
        Message message = delivery(51L);
        IllegalStateException failure = new IllegalStateException("connection reset");
        LtestatsConsumer consumer = consumer();
        doThrow(failure).when(processor).process(StatType.LTESTATS, message);

        consumer.consume(message, channel);

        InOrder order = inOrder(router, channel);
        order.verify(router).route(StatType.LTESTATS, message, failure);
        order.verify(channel).basicAck(51L, false);
        order.verifyNoMoreInteractions();
        assertAttemptMetered();
    }

    @Test
    void returnsTheDeliveryToItsQueueWhenTheHandOffCannotBeConfirmed() throws Exception {
        Channel channel = mock(Channel.class);
        Message message = delivery(73L);
        IllegalStateException failure = new IllegalStateException("database transaction failed");
        LtestatsConsumer consumer = consumer();
        doThrow(failure).when(processor).process(StatType.LTESTATS, message);
        UnconfirmedHandoffException unconfirmed =
                new UnconfirmedHandoffException("retry copy was not confirmed");
        doThrow(unconfirmed).when(router).route(StatType.LTESTATS, message, failure);

        consumer.consume(message, channel);

        // Never ACKed — the onward copy may not exist. And not left unacknowledged on the
        // open channel either: in MANUAL mode the container never returns it, so it was
        // not redelivered at all and held a prefetch slot for good. The router
        // returns it to its queue after a pause.
        InOrder order = inOrder(router);
        order.verify(router).route(StatType.LTESTATS, message, failure);
        order.verify(router).requeueUnconfirmed(StatType.LTESTATS, message, channel);
        org.mockito.Mockito.verify(channel, org.mockito.Mockito.never())
                .basicAck(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
        assertAttemptMetered();
    }

    @Test
    void shutdownInterruptionLeavesTheOriginalUnacknowledgedWithoutCreatingARetry() throws Exception {
        Message message = delivery(91L);
        InterruptedException shutdown = new InterruptedException("listener is stopping");
        LtestatsConsumer consumer = consumer();
        doThrow(shutdown).when(processor).process(StatType.LTESTATS, message);
        Channel channel = mock(Channel.class);

        try {
            assertThatThrownBy(() -> consumer.consume(message, channel)).isSameAs(shutdown);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        verifyNoInteractions(router, channel);
        assertAttemptMetered();
    }

    /** The age is sampled at entry under THIS type's label. */
    @Test
    void samplesTheDeliverysAgeUnderItsOwnType() throws Exception {
        Message message = delivery(7L);
        message.getMessageProperties()
                .setTimestamp(new java.util.Date(System.currentTimeMillis() - 30_000L));

        consumer().consume(message, mock(Channel.class));

        assertThat(registry.get(QueueAgeMetrics.OLDEST_MESSAGE_AGE).tag("type", "ltestats").gauge().value())
                .isBetween(29.0d, 32.0d);
        assertThat(registry.find(QueueAgeMetrics.OLDEST_MESSAGE_AGE).tag("type", "lorastats").gauge())
                .isNull();
    }

    @Test
    void rejectsAMissingCollaboratorInsteadOfConsumingWithoutIt() {
        assertThatNullPointerException()
                .isThrownBy(() -> new LtestatsConsumer(null, router, registry, queueAge))
                .withMessage("processorAvailability");
        assertThatNullPointerException()
                .isThrownBy(() -> new LtestatsConsumer(availability, null, registry, queueAge))
                .withMessage("failedDeliveryRouter");
        assertThatNullPointerException()
                .isThrownBy(() -> new LtestatsConsumer(availability, router, registry, null))
                .withMessage("queueAge");
    }

    private static Message delivery(long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        return new Message("{\"type\":\"ltestats\"}".getBytes(UTF_8), properties);
    }
}
