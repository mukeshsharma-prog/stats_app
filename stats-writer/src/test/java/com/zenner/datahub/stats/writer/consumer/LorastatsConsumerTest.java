package com.zenner.datahub.stats.writer.consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.writer.metrics.QueueAgeMetrics;
import java.time.Clock;
import com.zenner.datahub.stats.writer.retry.FailedDeliveryRouter;
import com.zenner.datahub.stats.writer.retry.UnconfirmedHandoffException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class LorastatsConsumerTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final QueueAgeMetrics queueAge = new QueueAgeMetrics(registry, Clock.systemUTC());

    private void assertAttemptMetered() {
        assertThat(registry.get("records_consumed").tag("type", "lorastats").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("records_processing_duration").tag("type", "lorastats").timer().count())
                .isEqualTo(1);
    }

    @Test
    void acknowledgesOnlyAfterTheProcessorReturnsSuccessfully() throws Exception {
        StatsMessageProcessor processor = mock(StatsMessageProcessor.class);
        StatsMessageProcessorAvailability availability =
                mock(StatsMessageProcessorAvailability.class);
        when(availability.requiredProcessor(StatType.LORASTATS)).thenReturn(processor);
        Channel channel = mock(Channel.class);
        Message message = delivery(42L);
        FailedDeliveryRouter router = mock(FailedDeliveryRouter.class);
        LorastatsConsumer consumer = new LorastatsConsumer(availability, router, registry, queueAge);

        consumer.consume(message, channel);

        InOrder order = inOrder(processor, channel);
        order.verify(processor).process(StatType.LORASTATS, message);
        order.verify(channel).basicAck(42L, false);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(router);
        assertAttemptMetered();
    }

    @Test
    void routesAFailedDeliveryOnwardBeforeAcknowledgingIt() throws Exception {
        // The ACK ordering hazard, asserted as an ORDER and not just as two calls.
        // ACK-then-publish leaves a window in which the original is gone and the copy
        // does not exist yet, and a crash inside it loses the record silently.
        StatsMessageProcessor processor = mock(StatsMessageProcessor.class);
        StatsMessageProcessorAvailability availability =
                mock(StatsMessageProcessorAvailability.class);
        when(availability.requiredProcessor(StatType.LORASTATS)).thenReturn(processor);
        Channel channel = mock(Channel.class);
        Message message = delivery(51L);
        IllegalStateException failure = new IllegalStateException("connection reset");
        org.mockito.Mockito.doThrow(failure)
                .when(processor)
                .process(StatType.LORASTATS, message);
        FailedDeliveryRouter router = mock(FailedDeliveryRouter.class);
        LorastatsConsumer consumer = new LorastatsConsumer(availability, router, registry, queueAge);

        consumer.consume(message, channel);

        InOrder order = inOrder(router, channel);
        order.verify(router).route(StatType.LORASTATS, message, failure);
        order.verify(channel).basicAck(51L, false);
        order.verifyNoMoreInteractions();
        assertAttemptMetered();
    }

    @Test
    void returnsTheDeliveryToItsQueueWhenTheHandOffCannotBeConfirmed() throws Exception {
        StatsMessageProcessor processor = mock(StatsMessageProcessor.class);
        StatsMessageProcessorAvailability availability =
                mock(StatsMessageProcessorAvailability.class);
        when(availability.requiredProcessor(StatType.LORASTATS)).thenReturn(processor);
        Channel channel = mock(Channel.class);
        Message message = delivery(73L);
        IllegalStateException failure = new IllegalStateException("database transaction failed");
        org.mockito.Mockito.doThrow(failure)
                .when(processor)
                .process(StatType.LORASTATS, message);
        FailedDeliveryRouter router = mock(FailedDeliveryRouter.class);
        UnconfirmedHandoffException unconfirmed =
                new UnconfirmedHandoffException("retry copy was not confirmed");
        org.mockito.Mockito.doThrow(unconfirmed)
                .when(router)
                .route(StatType.LORASTATS, message, failure);
        LorastatsConsumer consumer = new LorastatsConsumer(availability, router, registry, queueAge);

        consumer.consume(message, channel);

        // Never ACKed — the onward copy may not exist. And not left unacknowledged on the
        // open channel either: in MANUAL mode the container never returns it, so it was
        // not redelivered at all and held a prefetch slot for good. The router
        // returns it to its queue after a pause.
        InOrder order = inOrder(router);
        order.verify(router).route(StatType.LORASTATS, message, failure);
        order.verify(router).requeueUnconfirmed(StatType.LORASTATS, message, channel);
        org.mockito.Mockito.verify(channel, org.mockito.Mockito.never())
                .basicAck(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
        assertAttemptMetered();
    }

    @Test
    void shutdownInterruptionLeavesTheOriginalUnacknowledgedWithoutCreatingARetry() throws Exception {
        StatsMessageProcessor processor = mock(StatsMessageProcessor.class);
        StatsMessageProcessorAvailability availability =
                mock(StatsMessageProcessorAvailability.class);
        when(availability.requiredProcessor(StatType.LORASTATS)).thenReturn(processor);
        Message message = delivery(91L);
        InterruptedException shutdown = new InterruptedException("listener is stopping");
        org.mockito.Mockito.doThrow(shutdown)
                .when(processor)
                .process(StatType.LORASTATS, message);
        Channel channel = mock(Channel.class);
        FailedDeliveryRouter router = mock(FailedDeliveryRouter.class);
        LorastatsConsumer consumer = new LorastatsConsumer(availability, router, registry, queueAge);

        try {
            assertThatThrownBy(() -> consumer.consume(message, channel)).isSameAs(shutdown);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        verifyNoInteractions(router, channel);
        assertAttemptMetered();
    }

    /** The age is sampled at entry, before the processor runs. */
    @Test
    void samplesTheDeliverysAgeBeforeProcessingIt() throws Exception {
        StatsMessageProcessor processor = mock(StatsMessageProcessor.class);
        StatsMessageProcessorAvailability availability =
                mock(StatsMessageProcessorAvailability.class);
        when(availability.requiredProcessor(StatType.LORASTATS)).thenReturn(processor);
        Message message = delivery(7L);
        message.getMessageProperties()
                .setTimestamp(new java.util.Date(System.currentTimeMillis() - 45_000L));
        LorastatsConsumer consumer =
                new LorastatsConsumer(availability, mock(FailedDeliveryRouter.class), registry, queueAge);

        consumer.consume(message, mock(Channel.class));

        assertThat(
                        registry.get(com.zenner.datahub.stats.writer.metrics.QueueAgeMetrics.OLDEST_MESSAGE_AGE)
                                .tag("type", "lorastats")
                                .gauge()
                                .value())
                .isBetween(44.0d, 47.0d);
    }

    @Test
    void rejectsAMissingProcessorInsteadOfCreatingANoOpConsumer() {
        assertThatNullPointerException()
                .isThrownBy(() -> new LorastatsConsumer(null, mock(FailedDeliveryRouter.class), registry, queueAge))
                .withMessage("processorAvailability");
    }

    @Test
    void rejectsAMissingRouterInsteadOfConsumingWithNoFailureArm() {
        // A consumer with no router would throw on failure and requeue forever, which
        // is the failure the router exists to prevent, and is why it must not be constructible.
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new LorastatsConsumer(
                                        mock(StatsMessageProcessorAvailability.class),
                                        null,
                                        registry,
                                        queueAge))
                .withMessage("failedDeliveryRouter");
    }

    private static Message delivery(long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        return new Message("{\"type\":\"lorastats\"}".getBytes(UTF_8), properties);
    }
}
