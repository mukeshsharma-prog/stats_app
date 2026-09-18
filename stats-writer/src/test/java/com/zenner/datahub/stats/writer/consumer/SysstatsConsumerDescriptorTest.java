package com.zenner.datahub.stats.writer.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.common.model.StatType;
import org.junit.jupiter.api.Test;

class SysstatsConsumerDescriptorTest {

    /** The compile-time duplicate of the wire literal must never drift from the enum. */
    @Test
    void theCompileTimeWireLiteralMatchesTheEnum() {
        assertThat(SysstatsConsumerDescriptor.WIRE_NAME)
                .isEqualTo(SysstatsConsumerDescriptor.TYPE.wireName())
                .isEqualTo(StatType.SYSSTATS.wireName());
    }

    @Test
    void thePropertyPrefixAndBeanNameAreDerivedFromTheSameLiteral() {
        assertThat(SysstatsConsumerDescriptor.PROPERTY_PREFIX)
                .isEqualTo("stats.consumer." + StatType.SYSSTATS.wireName());
        assertThat(SysstatsConsumerDescriptor.BATCH_WRITER_BEAN)
                .isEqualTo(StatType.SYSSTATS.wireName() + "BatchWriter")
                .isNotEqualTo(LorastatsConsumerDescriptor.BATCH_WRITER_BEAN);
    }
}
