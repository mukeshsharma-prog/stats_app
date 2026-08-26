package com.zenner.datahub.stats.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Pattern;

/**
 * The common envelope shared by all five V1 statistic types.
 *
 * <p>D14 is explicit: unknown envelope fields are accepted, while unknown fields
 * inside {@code data} are preserved by its {@link JsonNode} representation. This
 * typed record is an application view; ingress validates and publishes the
 * original JSON byte-identically, so this view is not responsible for retaining
 * unknown top-level extensions.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StatsEnvelope(
        String uid,
        String tenant,
        String maintenant,
        @Pattern(regexp = "^[0-9A-Fa-f]{12}$") String gateway,
        StatType type,
        Timestamps ts,
        JsonNode data) {
}
