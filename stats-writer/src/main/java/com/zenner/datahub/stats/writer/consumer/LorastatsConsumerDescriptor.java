package com.zenner.datahub.stats.writer.consumer;

import com.zenner.datahub.stats.common.model.StatType;

/** The single type-specific identity point for the additive lorastats consumer artifact. */
final class LorastatsConsumerDescriptor {

    static final StatType TYPE = StatType.LORASTATS;
    static final String PROPERTY_PREFIX = "stats.consumer.lorastats";

    private LorastatsConsumerDescriptor() {
        throw new AssertionError("Descriptor must not be instantiated");
    }
}
