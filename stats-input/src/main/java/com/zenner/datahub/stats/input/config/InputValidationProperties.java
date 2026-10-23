package com.zenner.datahub.stats.input.config;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Input's validation policy — {@code stats.input.validation}.
 *
 * <p><strong>scope</strong> is enforced, not decorative. Input owns the
 * envelope gate only; the per-type payload belongs to the Writer, where a failure is
 * a redrivable dead-letter instead of a 400 that Heimdal will never retry. The
 * property once existed as documentation with nothing reading it, which is
 * exactly the state that lets a later refactor quietly widen the gate. Binding it and
 * rejecting any other value turns the comment into a startup guard.</p>
 *
 * <p><strong>maxClockSkew</strong> bounds how far {@code ts.device} may lead
 * {@code ts.server}. It is externalised rather than hard-coded, like every other
 * threshold: a tolerance that needs a rebuild to
 * change gets widened in an incident instead of tuned.</p>
 *
 * <p><strong>maxServerClockAhead</strong> bounds {@code ts.server} against this
 * instance's own clock. The skew check compares the record's two clocks with each other, so a
 * record whose clocks agreed with each other and were both decades ahead passed it,
 * was answered 200, and then found no partition at the Writer.</p>
 */
@ConfigurationProperties(prefix = "stats.input.validation")
public record InputValidationProperties(
        String intendedScope, Duration maxClockSkew, Duration maxServerClockAhead) {

    /** The only scope Input may run at. */
    public static final String ENVELOPE_SCOPE = "envelope";

    public InputValidationProperties {
        Objects.requireNonNull(intendedScope, "stats.input.validation.intended-scope");
        Objects.requireNonNull(maxClockSkew, "stats.input.validation.max-clock-skew");
        Objects.requireNonNull(
                maxServerClockAhead, "stats.input.validation.max-server-clock-ahead");

        if (!ENVELOPE_SCOPE.equals(intendedScope)) {
            // Refuse to start rather than run a gate nobody decided on. Validating the
            // payload here would convert a dialect disagreement into unrecoverable
            // data loss — production sends lorastats in three incompatible dialects
            // today, so this is not hypothetical.
            throw new IllegalArgumentException(
                    "stats.input.validation.intended-scope must be '" + ENVELOPE_SCOPE
                            + "' — Input validates the envelope only");
        }
        if (maxClockSkew.isNegative()) {
            throw new IllegalArgumentException(
                    "stats.input.validation.max-clock-skew must not be negative");
        }
        if (maxServerClockAhead.isNegative()) {
            throw new IllegalArgumentException(
                    "stats.input.validation.max-server-clock-ahead must not be negative");
        }
    }
}
