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
        when(availability.requiredProcessor()).thenReturn(processor);
        Channel channel = mock(Channel.class);
        Message message = delivery(42L);
        LorastatsConsumer consumer = new LorastatsConsumer(availability);

        consumer.consume(message, channel);

        InOrder order = inOrder(processor, channel);
        order.verify(processor).process(StatType.LORASTATS, message);
        order.verify(channel).basicAck(42L, false);
        order.verifyNoMoreInteractions();
    }

    @Test
    void leavesTheDeliveryUnacknowledgedWhenProcessingFails() throws Exception {
        StatsMessageProcessor processor = mock(StatsMessageProcessor.class);
        StatsMessageProcessorAvailability availability =
                mock(StatsMessageProcessorAvailability.class);
        when(availability.requiredProcessor()).thenReturn(processor);
        Channel channel = mock(Channel.class);
        Message message = delivery(73L);
        IllegalStateException failure = new IllegalStateException("database transaction failed");
        org.mockito.Mockito.doThrow(failure)
                .when(processor)
                .process(StatType.LORASTATS, message);
        LorastatsConsumer consumer = new LorastatsConsumer(availability);

        assertThatThrownBy(() -> consumer.consume(message, channel)).isSameAs(failure);

        verifyNoInteractions(channel);
    }

    @Test
    void rejectsAMissingProcessorInsteadOfCreatingANoOpConsumer() {
        assertThatNullPointerException()
                .isThrownBy(() -> new LorastatsConsumer(null))
                .withMessage("processorAvailability");
    }

    private static Message delivery(long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        return new Message("{\"type\":\"lorastats\"}".getBytes(UTF_8), properties);
    }
}
