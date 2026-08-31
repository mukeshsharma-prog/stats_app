package com.zenner.datahub.stats.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/**
 * The common envelope shared by all five V1 statistic types.
 *
 * <p>Field names are the production wire format, re-derived from the 29 August 2026
 * sample and ratified as ADR-007. The device identifier is {@code clsbox}, not
 * {@code gateway} — the SRS name that no production record has ever used. There is
 * deliberately no rename layer: the same name is used on the wire, here, in the
 * PostgreSQL column, and in API responses, because a translation step sitting next
 * to {@code tenant}/{@code maintenant} is exactly where a field gets silently
 * swapped (claude/CLAUDE.md §6).</p>
 *
 * <p>D14 is explicit: unknown envelope fields are accepted, while unknown fields
 * inside {@code data} are preserved by its {@link JsonNode} representation. This
 * typed record is an application view; ingress validates and publishes the
 * original JSON byte-identically, so this view is not responsible for retaining
 * unknown top-level extensions.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatsEnvelope(
        String uid,
        String tenant,
        String maintenant,
        @Pattern(regexp = "^[0-9A-Fa-f]{12}$") String clsbox,
        StatType type,

        /**
         * Heimdal's record version. Accepted and stored, and <strong>never</strong> a
         * payload discriminator: all three mutually incompatible lorastats payload
         * dialects observed in production carry {@code version: 1} (ADR-007 §4).
         */
        Integer version,

        Timestamps ts,
        JsonNode data,

        /**
         * Heimdal converter annotations, e.g. {@code ["conversion ok"]}. Preserved
         * verbatim and never interpreted — this is upstream state, not Stats App state.
         */
        List<String> status) {
}
