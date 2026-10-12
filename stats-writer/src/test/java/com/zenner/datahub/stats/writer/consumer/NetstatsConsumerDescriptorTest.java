package com.zenner.datahub.stats.writer.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.common.model.StatType;
import org.junit.jupiter.api.Test;

class NetstatsConsumerDescriptorTest {

    /** The compile-time duplicate of the wire literal must never drift from the enum. */
    @Test
    void theCompileTimeWireLiteralMatchesTheEnum() {
        assertThat(NetstatsConsumerDescriptor.WIRE_NAME)
                .isEqualTo(NetstatsConsumerDescriptor.TYPE.wireName())
                .isEqualTo(StatType.NETSTATS.wireName());
    }

    @Test
    void thePropertyPrefixAndBeanNameAreDerivedFromTheSameLiteral() {
        assertThat(NetstatsConsumerDescriptor.PROPERTY_PREFIX)
                .isEqualTo("stats.consumer." + StatType.NETSTATS.wireName());
        assertThat(NetstatsConsumerDescriptor.BATCH_WRITER_BEAN)
                .isEqualTo(StatType.NETSTATS.wireName() + "BatchWriter")
                .isNotEqualTo(LorastatsConsumerDescriptor.BATCH_WRITER_BEAN)
                .isNotEqualTo(SysstatsConsumerDescriptor.BATCH_WRITER_BEAN);
    }
}
