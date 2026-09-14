package com.zenner.datahub.stats.writer.consumer;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Externally tunable controls for the isolated lorastats consumer (STA-11A). */
@Validated
@ConfigurationProperties(LorastatsConsumerDescriptor.PROPERTY_PREFIX)
public record LorastatsConsumerProperties(
        @Min(1) int concurrency,
        @Min(1) int prefetch,
        Duration shutdownTimeout) {

    public LorastatsConsumerProperties {
        Objects.requireNonNull(shutdownTimeout, "stats.consumer.lorastats.shutdown-timeout");
        if (shutdownTimeout.isZero() || shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "stats.consumer.lorastats.shutdown-timeout must be positive");
        }
    }
}
