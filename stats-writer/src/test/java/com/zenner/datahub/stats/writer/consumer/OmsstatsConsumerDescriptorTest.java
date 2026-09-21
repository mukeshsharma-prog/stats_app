package com.zenner.datahub.stats.writer.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.common.model.StatType;
import org.junit.jupiter.api.Test;

class OmsstatsConsumerDescriptorTest {

    /** The compile-time duplicate of the wire literal must never drift from the enum. */
    @Test
    void theCompileTimeWireLiteralMatchesTheEnum() {
        assertThat(OmsstatsConsumerDescriptor.WIRE_NAME)
                .isEqualTo(OmsstatsConsumerDescriptor.TYPE.wireName())
                .isEqualTo(StatType.OMSSTATS.wireName());
    }

    @Test
    void thePropertyPrefixAndBeanNameAreDerivedFromTheSameLiteral() {
        assertThat(OmsstatsConsumerDescriptor.PROPERTY_PREFIX)
                .isEqualTo("stats.consumer." + StatType.OMSSTATS.wireName());
        assertThat(OmsstatsConsumerDescriptor.BATCH_WRITER_BEAN)
                .isEqualTo(StatType.OMSSTATS.wireName() + "BatchWriter")
                .isNotEqualTo(LorastatsConsumerDescriptor.BATCH_WRITER_BEAN)
                .isNotEqualTo(SysstatsConsumerDescriptor.BATCH_WRITER_BEAN);
    }
}
