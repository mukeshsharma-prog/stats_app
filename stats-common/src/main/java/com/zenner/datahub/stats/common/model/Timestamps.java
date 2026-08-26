package com.zenner.datahub.stats.common.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.time.Instant;

/**
 * Processor and device timestamps represented as Instants in Java and Unix
 * seconds in JSON.
 *
 * <p>The Draft 2020-12 envelope schema owns range validation and the deliberate
 * rejection of {@code ts.converter}. The model does not duplicate those checks;
 * ingress validates the original JSON before creating this typed view.</p>
 */
public record Timestamps(
        @JsonDeserialize(converter = EpochSecondToInstantConverter.class)
        @JsonSerialize(converter = InstantToEpochSecondConverter.class)
        Instant processor,

        @JsonDeserialize(converter = EpochSecondToInstantConverter.class)
        @JsonSerialize(converter = InstantToEpochSecondConverter.class)
        Instant device) {
}
