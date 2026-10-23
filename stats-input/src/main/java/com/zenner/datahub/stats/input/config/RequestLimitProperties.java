package com.zenner.datahub.stats.input.config;

import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Bounds on a single inbound request — {@code stats.input.limits}.
 *
 * <p><strong>ZC has not fixed these numbers, and that was never a reason to wait.</strong> The
 * inbound contract lists 413 and "429 or 503, exact policy pending" in its response
 * matrix but fixes no numbers. An unbounded ingest endpoint is wrong at every value
 * ZC can choose, so the values live in {@code application.properties} as engineering
 * defaults and this record carries no default of its own: ZC's answer is then a
 * property change, not a code change.</p>
 *
 * <p>{@code maxRequestsPerSecond} is <strong>per instance</strong>. The cluster ceiling
 * is this value times the replica count, and that is the number to reason about when
 * sizing against the 2× replay cap — Heimdal has agreed the cap, and an agreement is a
 * commitment rather than a control. This limit and the 503 backpressure of an
 * unconfirmed publish are the controls we actually own.</p>
 */
@ConfigurationProperties(prefix = "stats.input.limits")
public record RequestLimitProperties(DataSize maxRequestBytes, int maxRequestsPerSecond) {

    public RequestLimitProperties {
        Objects.requireNonNull(maxRequestBytes, "stats.input.limits.max-request-bytes");
        if (maxRequestBytes.toBytes() <= 0) {
            throw new IllegalArgumentException(
                    "stats.input.limits.max-request-bytes must be positive — a zero or negative "
                            + "bound rejects every record Heimdal sends");
        }
        if (maxRequestsPerSecond <= 0) {
            throw new IllegalArgumentException(
                    "stats.input.limits.max-requests-per-second must be positive — a zero limit is "
                            + "an outage, not a rate limit");
        }
    }
}
