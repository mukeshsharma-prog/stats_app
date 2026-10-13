package com.zenner.datahub.stats.writer.consumer;

import com.rabbitmq.client.Channel;
import com.zenner.datahub.stats.writer.log.WriterLogContext;
import com.zenner.datahub.stats.writer.metrics.QueueAgeMetrics;
import com.zenner.datahub.stats.writer.retry.FailedDeliveryRouter;
import com.zenner.datahub.stats.writer.retry.UnconfirmedHandoffException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * Dedicated consumer for {@code app_statsstore.sysstats}; it never handles another type
 * (T-035, DL-148; STA-20A, STA-25A).
 *
 * <p>The two ACK orderings are the lorastats sibling's, unchanged: on success the ACK
 * follows the committed transaction (D7); on failure it follows the <em>confirmed</em>
 * hand-off to a retry tier or the DLQ (ADR-005), and an unconfirmed hand-off leaves the
 * delivery with the broker. Nothing acknowledges a record that exists in exactly one
 * place.</p>
 */
public final class SysstatsConsumer {

    static final String LISTENER_ID = "stats-writer-sysstats";
    static final String CONTAINER_FACTORY = "sysstatsRabbitListenerContainerFactory";

    private final StatsMessageProcessorAvailability processorAvailability;
    private final FailedDeliveryRouter failedDeliveryRouter;
    private final MeterRegistry registry;
    private final QueueAgeMetrics queueAge;
    private final Counter consumed;
    private final Timer duration;

    public SysstatsConsumer(
            StatsMessageProcessorAvailability processorAvailability,
            FailedDeliveryRouter failedDeliveryRouter,
            MeterRegistry registry,
            QueueAgeMetrics queueAge) {
        this.processorAvailability =
                Objects.requireNonNull(processorAvailability, "processorAvailability");
        this.failedDeliveryRouter =
                Objects.requireNonNull(failedDeliveryRouter, "failedDeliveryRouter");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.queueAge = Objects.requireNonNull(queueAge, "queueAge");
        this.consumed = Counter.builder("records_consumed")
                .description("Broker deliveries entering processing, including redeliveries and malformed bodies")
                .tag("type", SysstatsConsumerDescriptor.WIRE_NAME).register(registry);
        this.duration = Timer.builder("records_processing_duration")
                .description("Delivery processing time including persistence, failure hand-off and ACK attempts")
                .tag("type", SysstatsConsumerDescriptor.WIRE_NAME).register(registry);
    }

    @RabbitListener(
            id = LISTENER_ID,
            queues = "#{@sysstatsMainQueue.name}",
            containerFactory = CONTAINER_FACTORY,
            ackMode = "MANUAL",
            autoStartup =
                    StatTypeDeployment.AUTO_STARTUP_PREFIX
                            + SysstatsConsumerDescriptor.WIRE_NAME
                            + StatTypeDeployment.AUTO_STARTUP_SUFFIX)
    public void consume(Message message, Channel channel) throws Exception {
        // Count before decoding so malformed records remain observable.
        consumed.increment();
        // Sampled at entry: this delivery was the head of a FIFO queue when the broker
        // handed it over, so its age is the queue's oldest-message age (T-050, DL-147).
        queueAge.observe(SysstatsConsumerDescriptor.TYPE, message);
        Timer.Sample sample = Timer.start(registry);
        try (WriterLogContext.Scope ignored =
                WriterLogContext.open(message, SysstatsConsumerDescriptor.TYPE)) {
            try {
                processorAvailability
                        .requiredProcessor(SysstatsConsumerDescriptor.TYPE)
                        .process(SysstatsConsumerDescriptor.TYPE, message);
            } catch (InterruptedException shutdown) {
                // Lifecycle, not a failed record: leave the original unacknowledged so
                // closing the channel returns it to its main queue (DL-143).
                Thread.currentThread().interrupt();
                throw shutdown;
            } catch (Exception failure) {
                try {
                    failedDeliveryRouter.route(SysstatsConsumerDescriptor.TYPE, message, failure);
                } catch (UnconfirmedHandoffException unconfirmed) {
                    // The onward copy may not exist, so the original must not be ACKed —
                    // and must not be stranded unacknowledged either (DL-168).
                    failedDeliveryRouter.requeueUnconfirmed(
                            SysstatsConsumerDescriptor.TYPE, message, channel);
                    return;
                }
                channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
                return;
            }
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } finally {
            sample.stop(duration);
        }
    }
}
