package com.zenner.datahub.stats.writer.consumer;

import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Keeps listener activation safe while the processing pipeline is delivered in stages.
 *
 * <p>T-019/T-020 creates the consumer before T-021/T-022 creates the database
 * processor. The listener container is registered now, but it starts only when a
 * real {@link StatsMessageProcessor} bean is present. This prevents an intermediate
 * deployment from consuming and acknowledging messages through a no-op handler.</p>
 */
public final class StatsMessageProcessorAvailability {

    private final ObjectProvider<StatsMessageProcessor> processors;

    public StatsMessageProcessorAvailability(ObjectProvider<StatsMessageProcessor> processors) {
        this.processors = Objects.requireNonNull(processors, "processors");
    }

    public boolean available() {
        return processors.getIfAvailable() != null;
    }

    StatsMessageProcessor requiredProcessor() {
        return processors.getObject();
    }
}
