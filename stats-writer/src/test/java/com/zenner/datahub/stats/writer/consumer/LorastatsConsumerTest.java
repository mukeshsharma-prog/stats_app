package com.zenner.datahub.stats.writer.consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.writer.retry.FailedDeliveryRouter;
import com.zenner.datahub.stats.writer.retry.UnconfirmedHandoffException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class LorastatsConsumerTest {

    @Test
    void acknowledgesOnlyAfterTheProcessorReturnsSuccessfully() throws Exception {
        StatsMessageProcessor processor = mock(StatsMessageProcessor.class);
        StatsMessageProcessorAvailability availability =
                mock(StatsMessageProcessorAvailability.class);
        when(availability.requiredProcessor(StatType.LORASTATS)).thenReturn(processor);
        Channel channel = mock(Channel.class);
        Message message = delivery(42L);
        FailedDeliveryRouter router = mock(FailedDeliveryRouter.class);
        LorastatsConsumer consumer = new LorastatsConsumer(availability, router);

        consumer.consume(message, channel);

        InOrder order = inOrder(processor, channel);
        order.verify(processor).process(StatType.LORASTATS, message);
        order.verify(channel).basicAck(42L, false);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(router);
    }

    @Test
    void routesAFailedDeliveryOnwardBeforeAcknowledgingIt() throws Exception {
        // ADR-005's ordering hazard, asserted as an ORDER and not just as two calls.
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
        LorastatsConsumer consumer = new LorastatsConsumer(availability, router);

        consumer.consume(message, channel);

        InOrder order = inOrder(router, channel);
        order.verify(router).route(StatType.LORASTATS, message, failure);
        order.verify(channel).basicAck(51L, false);
        order.verifyNoMoreInteractions();
    }

    @Test
    void leavesTheDeliveryUnacknowledgedWhenTheHandOffCannotBeConfirmed() throws Exception {
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
        LorastatsConsumer consumer = new LorastatsConsumer(availability, router);

        assertThatThrownBy(() -> consumer.consume(message, channel)).isSameAs(unconfirmed);

        // Nothing was acknowledged, so the broker still holds the only copy. A hot
        // redelivery loop is visible and recoverable; a lost record is neither.
        verifyNoInteractions(channel);
    }

    @Test
    void rejectsAMissingProcessorInsteadOfCreatingANoOpConsumer() {
        assertThatNullPointerException()
                .isThrownBy(() -> new LorastatsConsumer(null, mock(FailedDeliveryRouter.class)))
                .withMessage("processorAvailability");
    }

    @Test
    void rejectsAMissingRouterInsteadOfConsumingWithNoFailureArm() {
        // A consumer with no router would throw on failure and requeue forever, which
        // looks like the pre-T-024 behaviour and is why it must not be constructible.
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new LorastatsConsumer(
                                        mock(StatsMessageProcessorAvailability.class), null))
                .withMessage("failedDeliveryRouter");
    }

    private static Message delivery(long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        return new Message("{\"type\":\"lorastats\"}".getBytes(UTF_8), properties);
    }
}
