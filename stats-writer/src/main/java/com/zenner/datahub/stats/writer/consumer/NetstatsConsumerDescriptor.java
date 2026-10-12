package com.zenner.datahub.stats.writer.consumer;

import com.zenner.datahub.stats.common.model.StatType;

public final class NetstatsConsumerDescriptor {

    public static final StatType TYPE = StatType.NETSTATS;

    static final String WIRE_NAME = "netstats";

    static final String PROPERTY_PREFIX = "stats.consumer." + WIRE_NAME;

    public static final String BATCH_WRITER_BEAN = WIRE_NAME + "BatchWriter";

    private NetstatsConsumerDescriptor() {
        throw new AssertionError("Descriptor must not be instantiated");
    }
}
