package com.zenner.datahub.stats.common.amqp;

import com.zenner.datahub.stats.common.model.StatType;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical names and RabbitMQ argument keys for the Stats App V1 topology.
 *
 * <p>This class owns the resource <em>names</em> and the RabbitMQ <em>declaration
 * arguments</em> that go with them. Spring AMQP objects and operational values —
 * the retry delays themselves — still belong in the broker-connected application
 * modules, which read them from configuration and pass them in. Every type-specific
 * name derives from {@link StatType#wireName()} so adding a type cannot leave behind
 * a partially enumerated set of queues.</p>
 *
 * <p><strong>Why the argument maps live here and not in each module.</strong> Two
 * modules declaring the same queue with different arguments is not a warning — the
 * broker rejects the mismatched declaration with {@code RESOURCE_LOCKED} and the
 * module fails to start. Quorum sharpens it further: a queue's type is fixed at
 * declaration, so switching one means deleting and redeclaring it (ADR-002 §8).
 * Assembling the maps once here means the cross-module declaration test compares one
 * map rather than two hand-built ones. (CLAUDE.md §6; T-002, T-032)</p>
 *
 * <p><strong>The dead-letter queue takes no expiry and no onward route.</strong>
 * {@link #deadLetterQueueArguments()} has no parameter to set a TTL with, which is
 * deliberate: a TTL'd dead-letter queue discards records silently, and a message that
 * reached the DLQ has already survived three retry tiers — it is the only remaining
 * copy of a record Heimdal believes it delivered and will never resend. Bounding
 * broker disk is an alerting and disposition problem, not an expiry problem.
 * (ADR-002 §12, rule 9, trap 17)</p>
 */
public final class AmqpTopology {

    public static final String INPUT_EXCHANGE = "app_stats_input";
    public static final String DEAD_LETTER_EXCHANGE = "app_stats_dlx";

    public static final int RETRY_TIERS = 3;

    public static final String X_QUEUE_TYPE = "x-queue-type";
    public static final String QUORUM = "quorum";
    public static final String X_DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";
    public static final String X_MESSAGE_TTL = "x-message-ttl";
    public static final String X_DEAD_LETTER_ROUTING_KEY = "x-dead-letter-routing-key";

    private static final String QUEUE_PREFIX = "app_statsstore.";

    private AmqpTopology() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** The AMQP routing key is exactly the stat type's canonical wire literal. */
    public static String routingKey(StatType type) {
        return wireName(type);
    }

    public static String mainQueue(StatType type) {
        return QUEUE_PREFIX + wireName(type);
    }

    public static String retryQueue(StatType type, int tier) {
        requireValidTier(tier);
        return mainQueue(type) + ".retry." + tier;
    }

    public static String deadLetterQueue(StatType type) {
        return mainQueue(type) + ".dlq";
    }

    /**
     * Declaration arguments for every main queue {@code app_statsstore.<type>}.
     *
     * <p>Takes no stat type on purpose. The arguments are identical for all five
     * types, and keeping them that way is what makes rule 6 hold: a new type adds a
     * queue, never a new argument set to keep in step.</p>
     */
    public static Map<String, Object> mainQueueArguments() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put(X_QUEUE_TYPE, QUORUM);
        return Map.copyOf(arguments);
    }

    /**
     * Declaration arguments for one retry tier.
     *
     * <p>The delay is the tier's TTL and comes from configuration — never a constant
     * here (G4). When it expires the message dead-letters to {@link #DEAD_LETTER_EXCHANGE}
     * under the type's own routing key, and the binding that carries it back to the
     * main queue is declared by the broker-connected modules.</p>
     *
     * @param delay the tier delay; must be positive
     */
    public static Map<String, Object> retryQueueArguments(
            StatType type, int tier, Duration delay) {
        String routingKey = routingKey(type);
        requireValidTier(tier);
        Objects.requireNonNull(delay, "Retry tier delay must not be null");
        if (delay.isZero() || delay.isNegative()) {
            throw new IllegalArgumentException(
                    "Retry tier delay must be positive; immediate requeue is never used "
                            + "for a persistent failure");
        }

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put(X_QUEUE_TYPE, QUORUM);
        arguments.put(X_MESSAGE_TTL, delay.toMillis());
        arguments.put(X_DEAD_LETTER_EXCHANGE, DEAD_LETTER_EXCHANGE);
        arguments.put(X_DEAD_LETTER_ROUTING_KEY, routingKey);
        return Map.copyOf(arguments);
    }

    /**
     * Declaration arguments for every dead-letter queue {@code app_statsstore.<type>.dlq}.
     *
     * <p><strong>Quorum, and nothing else.</strong> No {@code x-message-ttl}, because an
     * expiry here is silent data loss. No {@code x-dead-letter-exchange}, because a DLQ
     * that routes onward is the same failure wearing a different name — the last copy of
     * the record leaves and nothing says so. There is no parameter to pass either one
     * through. (ADR-002 §12, trap 17)</p>
     */
    public static Map<String, Object> deadLetterQueueArguments() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put(X_QUEUE_TYPE, QUORUM);
        return Map.copyOf(arguments);
    }

    // --- Declaration definitions (T-032) -----------------------------------
    //
    // Names and argument maps were already shared; the DURABILITY and AUTO-DELETE
    // flags were not, and each module hand-built its own Spring AMQP object from
    // memory. Those flags are exactly as fatal as the arguments: a mismatch on any
    // one of them is RESOURCE_LOCKED at startup for whichever module boots second,
    // and quorum sharpens it further because a queue's type is fixed at declaration
    // (ADR-002 §8). Definitions below carry every field of a declaration, so the two
    // modules can differ only in the four lines that turn a definition into a Spring
    // object — and stats-testsupport's AmqpDeclarationAssert checks those in both.
    //
    // Deliberately free of Spring AMQP types: stats-common holds no broker client
    // (D15), and giving it one to share a factory would trade a small duplication
    // for a large dependency.

    /** Everything needed to declare one exchange, with no Spring AMQP types. */
    public record ExchangeDefinition(
            String name, String type, boolean durable, boolean autoDelete) {

        public static final String TOPIC = "topic";

        public ExchangeDefinition {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }
    }

    /** Everything needed to declare one queue, with no Spring AMQP types. */
    public record QueueDefinition(
            String name,
            boolean durable,
            boolean exclusive,
            boolean autoDelete,
            Map<String, Object> arguments) {

        public QueueDefinition {
            Objects.requireNonNull(name, "name");
            arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments"));
        }
    }

    /**
     * The ingress exchange every broker-connected module declares (ADR-002 §11).
     *
     * <p>Durable and not auto-delete: an auto-delete exchange disappears the moment the
     * last binding goes, and the next publish is then unroutable rather than queued.</p>
     */
    public static ExchangeDefinition inputExchange() {
        return new ExchangeDefinition(
                INPUT_EXCHANGE, ExchangeDefinition.TOPIC, true, false);
    }

    /**
     * The dead-letter exchange. It carries both the retry bounce-back and the
     * dead-letter route, separated by routing key — see {@link #deadLetterRoutingKey}.
     */
    public static ExchangeDefinition deadLetterExchange() {
        return new ExchangeDefinition(
                DEAD_LETTER_EXCHANGE, ExchangeDefinition.TOPIC, true, false);
    }

    /**
     * The routing key that carries a message to a type's DLQ, as distinct from
     * {@link #routingKey(StatType)}, which carries an expired retry message back to the
     * main queue. Both travel through {@link #DEAD_LETTER_EXCHANGE}: one key returns the
     * record for another attempt, the other retires it. Sharing a key would silently
     * turn every dead-letter into an infinite retry loop.
     */
    public static String deadLetterRoutingKey(StatType type) {
        return wireName(type) + ".dlq";
    }

    /** The main work queue, durable and non-exclusive (rule 10, STA-13A). */
    public static QueueDefinition mainQueueDefinition(StatType type) {
        return new QueueDefinition(mainQueue(type), true, false, false, mainQueueArguments());
    }

    /** One retry tier, holding messages for {@code delay} before bouncing them back. */
    public static QueueDefinition retryQueueDefinition(StatType type, int tier, Duration delay) {
        return new QueueDefinition(
                retryQueue(type, tier), true, false, false, retryQueueArguments(type, tier, delay));
    }

    /** The DLQ. No expiry and no onward route, by construction (ADR-002 §12). */
    public static QueueDefinition deadLetterQueueDefinition(StatType type) {
        return new QueueDefinition(
                deadLetterQueue(type), true, false, false, deadLetterQueueArguments());
    }

    private static String wireName(StatType type) {
        return Objects.requireNonNull(type, "Stat type must not be null").wireName();
    }

    private static void requireValidTier(int tier) {
        if (tier < 1 || tier > RETRY_TIERS) {
            throw new IllegalArgumentException(
                    "Retry tier must be between 1 and " + RETRY_TIERS);
        }
    }
}
