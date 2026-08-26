package com.zenner.datahub.stats.common.amqp;

import com.zenner.datahub.stats.common.model.StatType;
import java.util.Objects;

/**
 * Canonical names and RabbitMQ argument keys for the Stats App V1 topology.
 *
 * <p>This class names resources only. Spring AMQP objects and operational values,
 * including retry delays, belong in the broker-connected application modules.
 * Every type-specific name derives from {@link StatType#wireName()} so adding a
 * type cannot leave behind a partially enumerated set of queues.</p>
 */
public final class AmqpTopology {

    public static final String INPUT_EXCHANGE = "app_stats_input";
    public static final String DEAD_LETTER_EXCHANGE = "app_stats_dlx";

    public static final int RETRY_TIERS = 3;

    public static final String X_QUEUE_TYPE = "x-queue-type";
    public static final String QUORUM = "quorum";
    public static final String X_DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";
    public static final String X_MESSAGE_TTL = "x-message-ttl";

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
