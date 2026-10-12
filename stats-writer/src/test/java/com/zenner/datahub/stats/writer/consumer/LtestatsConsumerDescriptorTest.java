package com.zenner.datahub.stats.writer.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.common.model.StatType;
import org.junit.jupiter.api.Test;

class LtestatsConsumerDescriptorTest {

    /** The compile-time duplicate of the wire literal must never drift from the enum. */
    @Test
    void theCompileTimeWireLiteralMatchesTheEnum() {
        assertThat(LtestatsConsumerDescriptor.WIRE_NAME)
                .isEqualTo(LtestatsConsumerDescriptor.TYPE.wireName())
                .isEqualTo(StatType.LTESTATS.wireName());
    }

    @Test
    void thePropertyPrefixAndBeanNameAreDerivedFromTheSameLiteral() {
        assertThat(LtestatsConsumerDescriptor.PROPERTY_PREFIX)
                .isEqualTo("stats.consumer." + StatType.LTESTATS.wireName());
        assertThat(LtestatsConsumerDescriptor.BATCH_WRITER_BEAN)
                .isEqualTo(StatType.LTESTATS.wireName() + "BatchWriter")
                .isNotEqualTo(LorastatsConsumerDescriptor.BATCH_WRITER_BEAN)
                .isNotEqualTo(SysstatsConsumerDescriptor.BATCH_WRITER_BEAN);
    }
}
