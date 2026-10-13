package com.zenner.datahub.stats.writer.retry;

import com.rabbitmq.client.Channel;
import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.writer.log.SafeFailure;
import com.zenner.datahub.stats.writer.log.WriterLogContext;
import java.io.IOException;
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

    /** Marks an operator-approved delivery that must never enter the retry tiers again. */
    public static final String MANUAL_REDRIVE_HEADER = "x-stats-manual-redrive";

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
        for (StatType type : StatType.values()) {
            metrics.registerFor(type);
        }
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
        boolean manualRedrive =
                Boolean.TRUE.equals(
                        headersOf(message.getMessageProperties()).get(MANUAL_REDRIVE_HEADER));

        if (manualRedrive || permanent || attempt > properties.maxRetries()) {
            deadLetter(statType, message, attempt, reason, permanent, failure);
            return;
        }
        retry(statType, message, attempt, reason, failure);
    }

    /**
     * Returns a delivery whose onward copy was NOT confirmed to its main queue — after a
     * pause, never immediately (DL-168).
     *
     * <p>Leaving it unacknowledged is not an option, though it looks like one. In
     * {@code MANUAL} ack mode the listener container neither acks nor nacks a delivery
     * whose listener threw, so the record stays assigned to this open channel until the
     * channel closes: never redelivered, and holding one prefetch slot. After
     * {@code prefetch} of them the consumer stops receiving altogether, and the gauges
     * that would show it read normally whenever the queue itself is healthy.</p>
     *
     * <p>Nack with requeue keeps the broker holding the only copy, which is the property
     * the hand-off exists to protect. The pause is rule 5: the cause — a missing retry or
     * DLQ queue, a broker refusing publishes under a resource alarm — usually persists,
     * and an immediate requeue would spin on it. Sleeping on the listener thread is
     * deliberate: it also slows this type's consumption while its failure arm is broken.
     * Interrupted during the pause (shutdown), the delivery is left unacknowledged and the
     * closing channel returns it.</p>
     */
    public void requeueUnconfirmed(StatType statType, Message message, Channel channel)
            throws IOException, InterruptedException {
        Objects.requireNonNull(statType, "statType");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(channel, "channel");

        metrics.handoffUnconfirmed(statType);
        WriterLogContext.errorCode("handoff_unconfirmed");
        log.atError()
                .addKeyValue("event", "handoff_unconfirmed")
                .addKeyValue("requeueDelay", properties.unconfirmedHandoffRequeueDelay())
                .log(
                        "The retry/DLQ copy was not confirmed; the original is returned to its "
                                + "main queue after the delay. The failure arm itself is broken "
                                + "- check the retry and DLQ queues and broker alarms (DL-168)");
        try {
            Thread.sleep(properties.unconfirmedHandoffRequeueDelay().toMillis());
        } catch (InterruptedException shutdown) {
            Thread.currentThread().interrupt();
            throw shutdown;
        }
        channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
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
        WriterLogContext.errorCode(reason);
        // The failure is DESCRIBED, never passed to the logger: a throwable argument
        // prints a stack whose driver message carries the offending row values
        // (guard P2, DEVELOPMENT.md §7). SafeFailure explains what survives and why.
        log.atWarn()
                .addKeyValue("event", "retried")
                .addKeyValue("attempt", attempt)
                .addKeyValue("maxRetries", properties.maxRetries())
                .addKeyValue("targetQueue", queue)
                .addKeyValue("retryDelay", properties.delayForTier(tier))
                .addKeyValue("cause", SafeFailure.describe(failure))
                .log(
                        "Write failed and was routed to a confirmed retry tier; the original is "
                                + "ACKed only after this hand-off (ADR-005)");
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
        WriterLogContext.errorCode(reason);
        log.atError()
                .addKeyValue("event", "dead_lettered")
                .addKeyValue("attempt", attempt)
                .addKeyValue("permanent", permanent)
                .addKeyValue("targetQueue", AmqpTopology.deadLetterQueue(statType))
                .addKeyValue("cause", SafeFailure.describe(failure))
                .log(
                        "Record dead-lettered after a confirmed hand-off. The queue has no TTL; "
                                + "disposition is manual within 14 days (ADR-002 §12)");
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
        properties.setCorrelationId(source.getCorrelationId());
        properties.setMessageId(source.getMessageId());
        // Input's publish time travels with the copy (DL-147). The age gauge ignores a
        // retry re-entry by its attempt header, so this changes nothing it measures; it
        // is what a broker-side reader of DLQ age would need, which DL-144 recorded as
        // the missing precondition for the DLQ half of "oldest-message age".
        properties.setTimestamp(source.getTimestamp());

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
