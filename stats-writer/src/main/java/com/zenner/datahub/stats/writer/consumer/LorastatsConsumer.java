package com.zenner.datahub.stats.writer.consumer;

import com.rabbitmq.client.Channel;
import java.util.Objects;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/** Dedicated consumer for {@code app_statsstore.lorastats}; it never handles another type. */
public final class LorastatsConsumer {

    static final String LISTENER_ID = "stats-writer-lorastats";
    static final String CONTAINER_FACTORY = "lorastatsRabbitListenerContainerFactory";

    private final StatsMessageProcessorAvailability processorAvailability;

    public LorastatsConsumer(StatsMessageProcessorAvailability processorAvailability) {
        this.processorAvailability =
                Objects.requireNonNull(processorAvailability, "processorAvailability");
    }

    /**
     * ACK ordering is intentional: {@link StatsMessageProcessor#process} must return
     * only after a durable commit or confirmed hand-off. If it throws, this method
     * never ACKs the delivery, so the broker retains the only safe copy.
     */
    @RabbitListener(
            id = LISTENER_ID,
            queues = "#{@lorastatsMainQueue.name}",
            containerFactory = CONTAINER_FACTORY,
            ackMode = "MANUAL",
            autoStartup =
                    "#{@statsMessageProcessorAvailability.available('"
                            + LorastatsConsumerDescriptor.WIRE_NAME
                            + "')}")
    public void consume(Message message, Channel channel) throws Exception {
        processorAvailability
                .requiredProcessor(LorastatsConsumerDescriptor.TYPE)
                .process(LorastatsConsumerDescriptor.TYPE, message);
        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }
}
