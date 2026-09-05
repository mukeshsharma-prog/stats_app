package com.zenner.datahub.stats.writer.retry;

import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.model.StatType;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
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
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends a failed delivery to the next retry tier or to the type's DLQ, and does not
 * return until that copy is confirmed (T-024; ADR-002 §4/§12, ADR-005, STA-12,
 * STA-25A).
 *
 * <h2>The ordering is the whole point</h2>
 *
 * <p>This method returns only once the broker has confirmed the onward copy. The
 * caller ACKs the original <em>after</em> that, never before. The reverse order —
 * ACK, then publish — opens a window in which the original is gone and the copy does
 * not exist yet, and a crash inside it loses the record with nothing anywhere
 * reporting a failure. That window is the eleventh of the eleven in
 * {@code claude/FLOW.md} §8, and it is the one that leaves no trace at all.</p>
 *
 * <h2>Attempts are counted in our own header</h2>
 *
 * <p>{@value #ATTEMPT_HEADER}, not RabbitMQ's {@code x-death}. Half of this loop is
 * broker-driven (a tier's TTL expiring dead-letters the message back to the main
 * queue, which does append {@code x-death}) and half is ours (this explicit publish,
 * which does not). Counting attempts from a field only one of the two halves
 * maintains gives a number that resets, and a record that never reaches the DLQ is a
 * record that retries forever.</p>
 *
 * <h2>The body is never touched</h2>
 *
 * <p>The original bytes are republished verbatim. A retried or redriven record has to
 * remain byte-identical to what Heimdal sent, because it must still collide on
 * {@code (ts_device, uid)} when it finally lands — ADR-004's byte-identical
 * publication is what makes ADR-003's duplicate suppression correct, and it has to
 * survive this path too. Only headers change.</p>
 */
@Component
public class FailedDeliveryRouter {

    /** How many times this record has been handed on. Ours, not the broker's. */
    public static final String ATTEMPT_HEADER = "x-stats-attempt";

    /** The failure class name — never a message, which can quote payload (STA-03A). */
    public static final String FAILURE_HEADER = "x-stats-failure";

    /** Set on a DLQ copy only: whether the failure was classified as permanent. */
    public static final String PERMANENT_HEADER = "x-stats-permanent";

    private static final Logger log = LoggerFactory.getLogger(FailedDeliveryRouter.class);

    private final RabbitTemplate rabbitTemplate;
    private final WriterAmqpProperties properties;
    private final FailureClassifier classifier;
    private final WriterFailureMetrics metrics;

    public FailedDeliveryRouter(
            RabbitTemplate rabbitTemplate,
            WriterAmqpProperties properties,
            FailureClassifier classifier,
            WriterFailureMetrics metrics) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Routes one failed delivery. Returns normally only when the onward copy is
     * confirmed; throws {@link UnconfirmedHandoffException} otherwise, so the caller
     * leaves the delivery unacknowledged and the broker keeps the only copy.
     */
    public void route(StatType statType, Message message, Throwable failure) {
        Objects.requireNonNull(statType, "statType");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(failure, "failure");

        int attempt = previousAttempts(message) + 1;
        boolean permanent = classifier.isPermanent(failure);
        String reason = classifier.reasonLabel(failure);

        if (permanent || attempt > properties.maxRetries()) {
            deadLetter(statType, message, attempt, reason, permanent, failure);
            return;
        }
        retry(statType, message, attempt, reason, failure);
    }

    private void retry(
            StatType statType, Message message, int attempt, String reason, Throwable failure) {
        // One attempt per tier (G4): attempt 1 waits tier 1, attempt 2 waits tier 2.
        int tier = attempt;
        String queue = AmqpTopology.retryQueue(statType, tier);

        // The tier queues are holding queues with no binding of their own — a message
        // is placed on one directly, through the default exchange, and leaves it only
        // when its TTL expires and the DLX bounces it back to the main queue.
        publishConfirmed(
                "", queue, copyWithHeaders(message, attempt, reason, null), statType, queue);

        metrics.retried(statType, tier);
        log.warn(
                "Write failed for type={}; attempt {} of {} routed to {} (delay {}), reason={}. "
                        + "The original is ACKed only now that this copy is confirmed (ADR-005)",
                statType.wireName(),
                attempt,
                properties.maxRetries(),
                queue,
                properties.delayForTier(tier),
                reason,
                failure);
    }

    private void deadLetter(
            StatType statType,
            Message message,
            int attempt,
            String reason,
            boolean permanent,
            Throwable failure) {

        String routingKey = AmqpTopology.deadLetterRoutingKey(statType);
        publishConfirmed(
                AmqpTopology.DEAD_LETTER_EXCHANGE,
                routingKey,
                copyWithHeaders(message, attempt, reason, permanent),
                statType,
                AmqpTopology.deadLetterQueue(statType));

        metrics.deadLettered(statType, reason);
        log.error(
                "Record DEAD-LETTERED to {}: type={} attempt={} permanent={} reason={}. "
                        + "The queue has no TTL, so this record waits for a person "
                        + "(ADR-002 §12); disposition is expected within 14 days and a redrive "
                        + "after ~90 days is a no-op, because ts.device will have aged past "
                        + "retention",
                AmqpTopology.deadLetterQueue(statType),
                statType.wireName(),
                attempt,
                permanent,
                reason,
                failure);
    }

    /**
     * Publishes and waits for the confirm, treating a return as a failure.
     *
     * <p>An unroutable retry or DLQ publish is the same silent-loss shape ADR-002 §9
     * closes at Input: the broker acks a message no queue wanted. Here it would mean
     * the retry or dead-letter queue is missing, and ACKing the original on the
     * strength of that ack would destroy the record.</p>
     */
    private void publishConfirmed(
            String exchange, String routingKey, Message message, StatType statType, String target) {

        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbitTemplate.send(exchange, routingKey, message, correlation);

            CorrelationData.Confirm confirm =
                    correlation
                            .getFuture()
                            .get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);

            if (confirm == null || !confirm.isAck()) {
                throw new UnconfirmedHandoffException(
                        "Hand-off to " + target + " for type " + statType.wireName()
                                + " was not confirmed by the broker; the original stays "
                                + "unacknowledged");
            }
            if (correlation.getReturned() != null) {
                throw new UnconfirmedHandoffException(
                        "Hand-off to " + target + " for type " + statType.wireName()
                                + " was returned as unroutable — the queue is missing or the "
                                + "topology is half-applied; the original stays unacknowledged");
            }
        } catch (TimeoutException e) {
            throw new UnconfirmedHandoffException(
                    "Hand-off to " + target + " for type " + statType.wireName()
                            + " was not confirmed within " + properties.confirmTimeout()
                            + "; the original stays unacknowledged");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnconfirmedHandoffException(
                    "Interrupted while confirming the hand-off to " + target
                            + "; the original stays unacknowledged");
        } catch (ExecutionException | AmqpException e) {
            throw new UnconfirmedHandoffException(
                    "Hand-off to " + target + " for type " + statType.wireName()
                            + " failed before confirmation (" + e.getClass().getSimpleName()
                            + "); the original stays unacknowledged");
        }
    }

    /** The body verbatim, the original headers, and our three bookkeeping headers. */
    private static Message copyWithHeaders(
            Message message, int attempt, String reason, Boolean permanent) {

        MessageProperties source = message.getMessageProperties();
        MessageProperties properties = new MessageProperties();

        Map<String, Object> headers = new LinkedHashMap<>(headersOf(source));
        // x-death is the broker's own record of dead-letter events and it re-adds it.
        // Carrying a stale copy forward would leave two accounts of the same history.
        headers.remove("x-death");
        headers.put(ATTEMPT_HEADER, attempt);
        headers.put(FAILURE_HEADER, reason);
        if (permanent != null) {
            headers.put(PERMANENT_HEADER, permanent);
        }
        headers.forEach(properties::setHeader);

        properties.setContentType(
                source.getContentType() == null
                        ? MessageProperties.CONTENT_TYPE_JSON
                        : source.getContentType());
        properties.setContentEncoding(
                source.getContentEncoding() == null
                        ? StandardCharsets.UTF_8.name()
                        : source.getContentEncoding());
        // Rule 10 / STA-13A. A transient copy in a durable retry queue survives exactly
        // as long as the broker process, which is not what "retry in 30 minutes" means.
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);

        return new Message(message.getBody(), properties);
    }

    private static Map<String, Object> headersOf(MessageProperties properties) {
        return properties.getHeaders() == null ? new HashMap<>() : properties.getHeaders();
    }

    /** Reads {@value #ATTEMPT_HEADER}; absent or unreadable means this is the first. */
    private static int previousAttempts(Message message) {
        Object header = headersOf(message.getMessageProperties()).get(ATTEMPT_HEADER);
        if (header instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        return 0;
    }
}
