package com.zenner.datahub.stats.writer.consumer;

import com.rabbitmq.client.Channel;
import com.zenner.datahub.stats.writer.metrics.QueueAgeMetrics;
import com.zenner.datahub.stats.writer.retry.FailedDeliveryRouter;
import com.zenner.datahub.stats.writer.log.WriterLogContext;
import java.util.Objects;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/** Dedicated consumer for {@code app_statsstore.lorastats}; it never handles another type. */
public final class LorastatsConsumer {

    static final String LISTENER_ID = "stats-writer-lorastats";
    static final String CONTAINER_FACTORY = "lorastatsRabbitListenerContainerFactory";

    private final StatsMessageProcessorAvailability processorAvailability;
    private final FailedDeliveryRouter failedDeliveryRouter;
    private final MeterRegistry registry;
    private final QueueAgeMetrics queueAge;
    private final Counter consumed;
    private final Timer duration;

    public LorastatsConsumer(
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
                .tag("type", LorastatsConsumerDescriptor.WIRE_NAME).register(registry);
        this.duration = Timer.builder("records_processing_duration")
                .description("Delivery processing time including persistence, failure hand-off and ACK attempts")
                .tag("type", LorastatsConsumerDescriptor.WIRE_NAME).register(registry);
    }

    /**
     * ACK ordering is intentional, and there are now two orderings to keep (T-024).
     *
     * <p>On success, {@link StatsMessageProcessor#process} has committed, so the ACK
     * follows the commit (D7, STA-25A). On failure, the delivery is handed to
     * {@link FailedDeliveryRouter}, which returns only once the retry or dead-letter
     * copy is <strong>confirmed</strong> — and only then is the original ACKed
     * (ADR-005). Nothing acknowledges a record that exists in exactly one place.</p>
     *
     * <p>If the hand-off itself cannot be confirmed the router throws, this method
     * never ACKs, and the broker keeps the only copy for redelivery. That is the
     * correct outcome even though it can redeliver a message repeatedly while the
     * broker is unhealthy: a hot redelivery loop is visible and recoverable, and the
     * alternative is a record that quietly ceases to exist.</p>
     */
    @RabbitListener(
            id = LISTENER_ID,
            queues = "#{@lorastatsMainQueue.name}",
            containerFactory = CONTAINER_FACTORY,
            ackMode = "MANUAL",
            autoStartup =
                    StatTypeDeployment.AUTO_STARTUP_PREFIX
                            + LorastatsConsumerDescriptor.WIRE_NAME
                            + StatTypeDeployment.AUTO_STARTUP_SUFFIX)
    public void consume(Message message, Channel channel) throws Exception {
        // Count before decoding so malformed records remain observable.
        consumed.increment();
        // Sampled at entry, before any work: this delivery was the head of a FIFO queue
        // when the broker handed it over, so its age IS the queue's oldest-message age
        // at that moment (T-050, DL-147).
        queueAge.observe(LorastatsConsumerDescriptor.TYPE, message);
        Timer.Sample sample = Timer.start(registry);
        try (WriterLogContext.Scope ignored =
                WriterLogContext.open(message, LorastatsConsumerDescriptor.TYPE)) {
            try {
                processorAvailability
                        .requiredProcessor(LorastatsConsumerDescriptor.TYPE)
                        .process(LorastatsConsumerDescriptor.TYPE, message);
            } catch (InterruptedException shutdown) {
                // Container shutdown uses interruption after its grace period. This is
                // not a failed record and must not spend a retry attempt: preserving the
                // interrupt and returning exceptionally leaves the original unacknowledged,
                // so closing the channel returns it to its main queue.
                Thread.currentThread().interrupt();
                throw shutdown;
            } catch (Exception failure) {
                failedDeliveryRouter.route(LorastatsConsumerDescriptor.TYPE, message, failure);
                channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
                return;
            }
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } finally {
            sample.stop(duration);
        }
    }
}
