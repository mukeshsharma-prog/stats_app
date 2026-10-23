package com.zenner.datahub.stats.writer.consumer;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Externally tunable controls for the isolated omsstats consumer. */
@Validated
@ConfigurationProperties(OmsstatsConsumerDescriptor.PROPERTY_PREFIX)
public record OmsstatsConsumerProperties(
        @Min(1) int concurrency,
        @Min(1) int prefetch,
        Duration shutdownTimeout) {

    public OmsstatsConsumerProperties {
        Objects.requireNonNull(shutdownTimeout, "stats.consumer.omsstats.shutdown-timeout");
        if (shutdownTimeout.isZero() || shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "stats.consumer.omsstats.shutdown-timeout must be positive");
        }
    }
}
