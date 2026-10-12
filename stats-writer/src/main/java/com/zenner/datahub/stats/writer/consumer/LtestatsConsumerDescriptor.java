package com.zenner.datahub.stats.writer.consumer;

import com.zenner.datahub.stats.common.model.StatType;

public final class LtestatsConsumerDescriptor {

    public static final StatType TYPE = StatType.LTESTATS;

    static final String WIRE_NAME = "ltestats";

    static final String PROPERTY_PREFIX = "stats.consumer." + WIRE_NAME;

    public static final String BATCH_WRITER_BEAN = WIRE_NAME + "BatchWriter";

    private LtestatsConsumerDescriptor() {
        throw new AssertionError("Descriptor must not be instantiated");
    }
}
