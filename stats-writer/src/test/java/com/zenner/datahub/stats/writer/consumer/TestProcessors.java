package com.zenner.datahub.stats.writer.consumer;

import com.zenner.datahub.stats.common.model.StatType;
import org.springframework.amqp.core.Message;

/** Minimal {@link StatsMessageProcessor} stubs for activation and selection tests. */
final class TestProcessors {

    private TestProcessors() {
        throw new AssertionError("Test helper must not be instantiated");
    }

    /** A processor that claims {@code type} and does nothing with the delivery. */
    static StatsMessageProcessor noOp(StatType type) {
        return new StatsMessageProcessor() {
            @Override
            public StatType statType() {
                return type;
            }

            @Override
            public void process(StatType expectedType, Message message) {
                // Deliberately empty: these tests assert selection and activation,
                // never processing behaviour.
            }
        };
    }
}
