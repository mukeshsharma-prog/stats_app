package com.zenner.datahub.stats.writer.consumer;

import com.zenner.datahub.stats.common.model.StatType;

/**
 * The single type-specific identity point for the additive sysstats consumer artifact.
 * A sibling of {@link LorastatsConsumerDescriptor}, never an edit to it.
 */
public final class SysstatsConsumerDescriptor {

    public static final StatType TYPE = StatType.SYSSTATS;

    /**
     * The same literal as {@link StatType#wireName()}, repeated as a compile-time
     * constant because annotation attributes cannot call a method.
     * {@code SysstatsConsumerDescriptorTest} asserts the two never diverge.
     */
    static final String WIRE_NAME = "sysstats";

    static final String PROPERTY_PREFIX = "stats.consumer." + WIRE_NAME;

    /** The bean name of this type's {@code StatsBatchWriter}, spelled once. */
    public static final String BATCH_WRITER_BEAN = WIRE_NAME + "BatchWriter";

    private SysstatsConsumerDescriptor() {
        throw new AssertionError("Descriptor must not be instantiated");
    }
}
