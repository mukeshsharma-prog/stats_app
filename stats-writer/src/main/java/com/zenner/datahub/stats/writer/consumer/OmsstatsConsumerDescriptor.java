package com.zenner.datahub.stats.writer.consumer;

import com.zenner.datahub.stats.common.model.StatType;

/**
 * The single type-specific identity point for the additive omsstats consumer artifact.
 * A sibling of {@link SysstatsConsumerDescriptor} and
 * {@link LorastatsConsumerDescriptor}, never an edit to either.
 */
public final class OmsstatsConsumerDescriptor {

    public static final StatType TYPE = StatType.OMSSTATS;

    /**
     * The same literal as {@link StatType#wireName()}, repeated as a compile-time
     * constant because annotation attributes cannot call a method.
     * {@code OmsstatsConsumerDescriptorTest} asserts the two never diverge.
     */
    static final String WIRE_NAME = "omsstats";

    static final String PROPERTY_PREFIX = "stats.consumer." + WIRE_NAME;

    /** The bean name of this type's {@code StatsBatchWriter}, spelled once. */
    public static final String BATCH_WRITER_BEAN = WIRE_NAME + "BatchWriter";

    private OmsstatsConsumerDescriptor() {
        throw new AssertionError("Descriptor must not be instantiated");
    }
}
