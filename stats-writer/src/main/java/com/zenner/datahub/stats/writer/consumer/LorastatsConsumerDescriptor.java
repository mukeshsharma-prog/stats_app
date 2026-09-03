package com.zenner.datahub.stats.writer.consumer;

import com.zenner.datahub.stats.common.model.StatType;

/** The single type-specific identity point for the additive lorastats consumer artifact. */
public final class LorastatsConsumerDescriptor {

    public static final StatType TYPE = StatType.LORASTATS;

    /**
     * The same literal as {@link StatType#wireName()}, repeated as a compile-time
     * constant because annotation attributes cannot call a method.
     * {@code LorastatsConsumerDescriptorTest} asserts the two never diverge.
     */
    static final String WIRE_NAME = "lorastats";

    static final String PROPERTY_PREFIX = "stats.consumer." + WIRE_NAME;

    private LorastatsConsumerDescriptor() {
        throw new AssertionError("Descriptor must not be instantiated");
    }
}
