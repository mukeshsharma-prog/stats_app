package com.zenner.datahub.stats.writer.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.common.model.StatType;
import org.junit.jupiter.api.Test;

class LorastatsConsumerDescriptorTest {

    /**
     * {@code WIRE_NAME} is duplicated from {@link StatType#wireName()} only because
     * annotation attributes cannot call a method — the {@code autoStartup} SpEL on
     * {@code @RabbitListener} needs a compile-time constant. This test is what keeps
     * the duplicate honest: if the two ever diverge the listener would ask about a
     * stat type that does not exist and silently never start.
     */
    @Test
    void theCompileTimeWireLiteralMatchesTheEnum() {
        assertThat(LorastatsConsumerDescriptor.WIRE_NAME)
                .isEqualTo(LorastatsConsumerDescriptor.TYPE.wireName())
                .isEqualTo(StatType.LORASTATS.wireName());
    }

    @Test
    void thePropertyPrefixIsDerivedFromTheSameLiteral() {
        assertThat(LorastatsConsumerDescriptor.PROPERTY_PREFIX)
                .isEqualTo("stats.consumer." + StatType.LORASTATS.wireName());
    }
}
