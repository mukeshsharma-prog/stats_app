package com.zenner.datahub.stats.writer.consumer;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Externally tunable controls for the isolated sysstats consumer. */
@Validated
@ConfigurationProperties(SysstatsConsumerDescriptor.PROPERTY_PREFIX)
public record SysstatsConsumerProperties(
        @Min(1) int concurrency,
        @Min(1) int prefetch,
        Duration shutdownTimeout) {

    public SysstatsConsumerProperties {
        Objects.requireNonNull(shutdownTimeout, "stats.consumer.sysstats.shutdown-timeout");
        if (shutdownTimeout.isZero() || shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "stats.consumer.sysstats.shutdown-timeout must be positive");
        }
    }
}
