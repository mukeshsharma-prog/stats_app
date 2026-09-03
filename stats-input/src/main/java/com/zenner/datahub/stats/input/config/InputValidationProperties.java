package com.zenner.datahub.stats.input.config;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Input's validation policy — {@code stats.input.validation}.
 *
 * <p><strong>scope</strong> is enforced, not decorative. ADR-007 §5 gives Input the
 * envelope gate only; the per-type payload belongs to the Writer, where a failure is
 * a redrivable dead-letter instead of a 400 that Heimdal will never retry. The
 * property existed before T-013 as documentation with nothing reading it, which is
 * exactly the state that lets a later refactor quietly widen the gate. Binding it and
 * rejecting any other value turns the comment into a startup guard (DL-101).</p>
 *
 * <p><strong>maxClockSkew</strong> closes Q14a (ADR-003 D9). It is externalised
 * rather than hard-coded, on the G4 precedent: a tolerance that needs a rebuild to
 * change gets widened in an incident instead of tuned.</p>
 */
@ConfigurationProperties(prefix = "stats.input.validation")
public record InputValidationProperties(String intendedScope, Duration maxClockSkew) {

    /** The only scope Input may run at (ADR-007 §5). */
    public static final String ENVELOPE_SCOPE = "envelope";

    public InputValidationProperties {
        Objects.requireNonNull(intendedScope, "stats.input.validation.intended-scope");
        Objects.requireNonNull(maxClockSkew, "stats.input.validation.max-clock-skew");

        if (!ENVELOPE_SCOPE.equals(intendedScope)) {
            // Refuse to start rather than run a gate nobody decided on. Validating the
            // payload here would convert a dialect disagreement into unrecoverable
            // data loss — production sends lorastats in three incompatible dialects
            // today, so this is not hypothetical (ADR-007 §4).
            throw new IllegalArgumentException(
                    "stats.input.validation.intended-scope must be '" + ENVELOPE_SCOPE
                            + "' — Input validates the envelope only (ADR-007 §5)");
        }
        if (maxClockSkew.isNegative()) {
            throw new IllegalArgumentException(
                    "stats.input.validation.max-clock-skew must not be negative");
        }
    }
}
