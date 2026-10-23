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

    /**
     * The bean name of this type's {@code StatsBatchWriter}, spelled once. The writer
     * declares it and the processor qualifies on it, so a second type's writer in the
     * same context is not an ambiguity.
     */
    public static final String BATCH_WRITER_BEAN = WIRE_NAME + "BatchWriter";

    private LorastatsConsumerDescriptor() {
        throw new AssertionError("Descriptor must not be instantiated");
    }
}
