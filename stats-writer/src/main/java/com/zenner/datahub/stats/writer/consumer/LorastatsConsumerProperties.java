package com.zenner.datahub.stats.writer.consumer;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Externally tunable controls for the isolated lorastats consumer (STA-11A). */
@Validated
@ConfigurationProperties(LorastatsConsumerDescriptor.PROPERTY_PREFIX)
public record LorastatsConsumerProperties(
        @Min(1) int concurrency,
        @Min(1) int prefetch) {}
