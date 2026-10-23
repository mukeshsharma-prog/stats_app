package com.zenner.datahub.stats.input.publish;

import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.error.ErrorCode;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.input.ingest.IngestMetrics;
import com.zenner.datahub.stats.input.log.IngestLogContext;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Publishes to {@code app_stats_input} and returns only what the broker has actually
 * told us.
 *
 * <p>Three properties of this class are load-bearing, and each one closes a way the
 * system can lose a record without anybody noticing.</p>
 *
 * <h2>1. The bytes are sent unchanged</h2>
 *
 * <p>{@link RabbitTemplate#send} with a hand-built {@link Message}, never
 * {@code convertAndSend}. A converter would re-encode the record — key order,
 * number formatting and escaping are all fair game to Jackson — and publication has
 * to be byte-identical to what was received. That is not tidiness: the
 * Writer suppresses duplicates with {@code ON CONFLICT (ts_device, uid) DO NOTHING},
 * which is only correct if a redelivered record really is the same record.
 * {@code BytePreservingPublishIntegrationTest} is the proof.</p>
 *
 * <h2>2. CONFIRMED means the broker said so</h2>
 *
 * <p>The confirm is read from {@link CorrelationData#getFuture()} with a bounded
 * wait, because the template runs in {@code correlated} mode.
 * {@code waitForConfirms} is the {@code simple}-mode API and, mixed with this
 * configuration, answers about a batch this thread never published. Every other
 * ending — nack, timeout, interrupt, broker unreachable — collapses to
 * {@link PublishOutcome#NOT_CONFIRMED} and therefore to a 503. There is deliberately
 * no path on which an exception escapes to a generic handler and becomes a 500 with
 * no counter behind it.</p>
 *
 * <h2>3. An ack is not a delivery</h2>
 *
 * <p>A confirm says the broker accepted the message, not that any queue wanted it.
 * With no binding — a new type deployed before its queue, a typo, a half-applied
 * topology change — the broker acks and drops the record, and without
 * {@code mandatory=true} Input would answer 200 to a record that no longer exists.
 * Publishing mandatory makes the broker return it, and Spring attaches the returned
 * message to the same {@link CorrelationData} before completing its future, so an
 * ack carrying a non-null {@link CorrelationData#getReturned()} is a
 * <strong>failed</strong> acceptance: 503 plus {@code records_unroutable{type}}.
 * {@code UnroutablePublishIntegrationTest} pins that ordering against
 * this Spring AMQP version rather than trusting the reference documentation.</p>
 */
public final class ConfirmingStatsPublisher implements StatsPublisher {

    private static final Logger log = LoggerFactory.getLogger(ConfirmingStatsPublisher.class);

    /** The AMQP 0-9-1 short-string limit, which {@code messageId} is bound by. */
    static final int MAX_SHORT_STRING_BYTES = 255;

    private final RabbitTemplate rabbitTemplate;
    private final AmqpPublisherProperties properties;
    private final IngestMetrics metrics;

    public ConfirmingStatsPublisher(
            RabbitTemplate rabbitTemplate,
            AmqpPublisherProperties properties,
            IngestMetrics metrics) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        for (StatType type : StatType.values()) {
            // The p99 a dashboard reads must exist before the first record.
            metrics.registerPublishConfirm(type);
        }
    }

    @Override
    public PublishOutcome publish(StatType statType, byte[] body) {
        Objects.requireNonNull(statType, "statType");
        Objects.requireNonNull(body, "body");

        String routingKey = AmqpTopology.routingKey(statType);
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        long started = System.nanoTime();

        try {
            rabbitTemplate.send(properties.exchange(), routingKey, message(body), correlation);

            CorrelationData.Confirm confirm =
                    correlation
                            .getFuture()
                            .get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);

            if (confirm == null || !confirm.isAck()) {
                metrics.publishConfirm(statType, IngestMetrics.OUTCOME_NACK, Duration.ofNanos(System.nanoTime() - started));
                IngestLogContext.errorCode(ErrorCode.SERVICE_UNAVAILABLE);
                log.atError()
                        .addKeyValue("event", "publish_failed")
                        .addKeyValue("routingKey", routingKey)
                        .addKeyValue(
                                "confirmReason",
                                confirm == null ? "no_confirm" : "broker_nack")
                        .log("Broker NACKed the publish; answering 503 so Heimdal resends");
                return PublishOutcome.NOT_CONFIRMED;
            }

            ReturnedMessage returned = correlation.getReturned();
            if (returned != null) {
                metrics.publishConfirm(statType, IngestMetrics.OUTCOME_RETURNED, Duration.ofNanos(System.nanoTime() - started));
                // Acked AND returned: accepted by the broker, wanted by no queue.
                // The ERROR log with the routing key is emitted by the ReturnsCallback
                // in InputAmqpConfiguration; this is the counter and the decision.
                metrics.unroutable(statType);
                return PublishOutcome.NOT_CONFIRMED;
            }

            metrics.publishConfirm(statType, IngestMetrics.OUTCOME_CONFIRMED, Duration.ofNanos(System.nanoTime() - started));
            return PublishOutcome.CONFIRMED;

        } catch (TimeoutException e) {
            metrics.publishConfirm(statType, IngestMetrics.OUTCOME_TIMEOUT, Duration.ofNanos(System.nanoTime() - started));
            // We do not know whether it was published. 503 is the honest answer:
            // a duplicate on Heimdal's resend is absorbed by ON CONFLICT DO NOTHING,
            // whereas a 200 here would be a record nobody holds.
            IngestLogContext.errorCode(ErrorCode.SERVICE_UNAVAILABLE);
            log.atError()
                    .addKeyValue("event", "publish_failed")
                    .addKeyValue("routingKey", routingKey)
                    .addKeyValue("confirmTimeout", properties.confirmTimeout())
                    .log(
                            "Publisher confirm did not arrive within the configured timeout; "
                                    + "outcome unknown, answering 503");
            return PublishOutcome.NOT_CONFIRMED;
        } catch (InterruptedException e) {
            metrics.publishConfirm(statType, IngestMetrics.OUTCOME_ERROR, Duration.ofNanos(System.nanoTime() - started));
            Thread.currentThread().interrupt();
            IngestLogContext.errorCode(ErrorCode.SERVICE_UNAVAILABLE);
            log.atError()
                    .addKeyValue("event", "publish_failed")
                    .addKeyValue("routingKey", routingKey)
                    .addKeyValue("failureType", e.getClass().getSimpleName())
                    .log("Interrupted while waiting for the publisher confirm; answering 503");
            return PublishOutcome.NOT_CONFIRMED;
        } catch (ExecutionException | AmqpException e) {
            metrics.publishConfirm(statType, IngestMetrics.OUTCOME_ERROR, Duration.ofNanos(System.nanoTime() - started));
            IngestLogContext.errorCode(ErrorCode.SERVICE_UNAVAILABLE);
            log.atError()
                    .addKeyValue("event", "publish_failed")
                    .addKeyValue("routingKey", routingKey)
                    .addKeyValue("failureType", e.getClass().getSimpleName())
                    .log(
                            "Publish failed before confirmation; answering 503 so Heimdal resends "
                                    + "");
            return PublishOutcome.NOT_CONFIRMED;
        }
    }

    /**
     * Persistent, JSON, and carrying the received bytes verbatim.
     *
     * <p>{@code PERSISTENT} because durable queues hold transient
     * messages exactly as long as the broker process lives.</p>
     */
    /**
     * The {@code uid} as the AMQP {@code messageId}, or none when it cannot be one.
     *
     * <p>{@code messageId} is an AMQP short string: at most {@value #MAX_SHORT_STRING_BYTES}
     * bytes. A longer value fails the publish on the client, which answered 503 to a record
     * Heimdal then resent forever. The envelope caps {@code uid} at 128
     * characters, but 128 non-ASCII characters are still up to 512 bytes, so the bound is
     * checked in bytes here. Omitting the property is safe: the Writer reads it only to
     * seed its log scope, and binds the {@code uid} from the record itself.</p>
     */
    static String messageId(String uid) {
        if (uid == null || uid.getBytes(StandardCharsets.UTF_8).length > MAX_SHORT_STRING_BYTES) {
            return null;
        }
        return uid;
    }

    private static Message message(byte[] body) {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        messageProperties.setContentEncoding(StandardCharsets.UTF_8.name());
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        // Standard AMQP properties, not custom headers: Writer can restore correlation
        // before it parses the body, and retries can preserve them without changing the
        // byte-identical record body.
        messageProperties.setCorrelationId(IngestLogContext.traceId());
        messageProperties.setMessageId(messageId(IngestLogContext.uid()));
        // The publish time, as the standard AMQP `timestamp` property (whole seconds).
        // The Writer reads it at delivery to compute queue_oldest_message_age_seconds;
        // a property, not a header, so it rides with the retry and
        // DLQ copies the same way correlationId does. The body is untouched.
        messageProperties.setTimestamp(new Date());
        return new Message(body, messageProperties);
    }
}
