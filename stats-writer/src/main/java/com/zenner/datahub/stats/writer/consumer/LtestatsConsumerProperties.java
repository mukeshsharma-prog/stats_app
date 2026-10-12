package com.zenner.datahub.stats.writer.consumer;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(LtestatsConsumerDescriptor.PROPERTY_PREFIX)
public record LtestatsConsumerProperties(
        @Min(1) int concurrency,
        @Min(1) int prefetch,
        Duration shutdownTimeout) {

    public LtestatsConsumerProperties {
        Objects.requireNonNull(shutdownTimeout, "stats.consumer.ltestats.shutdown-timeout");
        if (shutdownTimeout.isZero() || shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "stats.consumer.ltestats.shutdown-timeout must be positive");
        }
    }
}
