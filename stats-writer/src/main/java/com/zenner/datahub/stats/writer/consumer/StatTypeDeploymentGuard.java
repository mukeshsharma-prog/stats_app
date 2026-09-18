package com.zenner.datahub.stats.writer.consumer;

import static com.zenner.datahub.stats.writer.consumer.StatTypeDeployment.ALL_TYPES;
import static com.zenner.datahub.stats.writer.consumer.StatTypeDeployment.STAT_TYPE_PROPERTY;

import com.zenner.datahub.stats.common.model.StatType;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start a Writer whose {@code stats.writer.stat-type} cannot be honoured.
 *
 * <p>ADR-003 D8 and T-019 both require <strong>exactly one stat type per deployment</strong>
 * in production: one image, deployed once per type, so prefetch, consumer concurrency and
 * replica count can be tuned per type (STA-11A, STA-20A, STA-26A). That is the entire
 * reason D8 chose per-type deployment over one Writer consuming everything.</p>
 *
 * <p>The property nevertheless defaults to {@code all}, which is right for a developer
 * running the whole stack on one machine and wrong for production — and until now
 * <strong>nothing refused it</strong>. An {@code all} deployment starts cleanly, consumes
 * every queue, silently defeats every per-type tuning knob, and reports nothing: the
 * failure is a capacity one that appears under load, long after the deploy that caused
 * it. (D-10, audited 4 Sep 2026.)</p>
 *
 * <p>This mirrors the {@code stats.input.auth.mode} precedent — a deliberately unsafe
 * default that is allowed to exist for local work and is made impossible under the
 * {@code prod} profile — and the {@code intended-scope} guard of DL-101: a property that
 * only documents an intention is one edit away from being wrong. Failing the context is
 * the point. A container that will not start is a deploy that rolls back; a Writer that
 * quietly consumes five queues is an incident three weeks later.</p>
 */
@Component
public class StatTypeDeploymentGuard {

    private static final Logger log = LoggerFactory.getLogger(StatTypeDeploymentGuard.class);

    /** The profile under which the all-types convenience mode is forbidden. */
    static final String PRODUCTION_PROFILE = "prod";

    StatTypeDeploymentGuard(Environment environment) {
        String configured = environment.getProperty(STAT_TYPE_PROPERTY, ALL_TYPES).trim();
        boolean production =
                List.of(environment.getActiveProfiles()).contains(PRODUCTION_PROFILE);

        if (!ALL_TYPES.equals(configured) && !isKnownType(configured)) {
            // A typo here is silent in the worst way: every per-type Condition evaluates
            // false, so the Writer starts, binds nothing, consumes nothing, and looks
            // healthy — readiness has no opinion about whether a listener exists.
            throw new IllegalArgumentException(
                    STAT_TYPE_PROPERTY + " is '" + configured + "', which is neither '"
                            + ALL_TYPES + "' nor a known stat type (" + knownTypes()
                            + "). A Writer with an unrecognised type consumes nothing and "
                            + "still starts.");
        }

        if (production && ALL_TYPES.equals(configured)) {
            throw new IllegalArgumentException(
                    STAT_TYPE_PROPERTY + "=" + ALL_TYPES + " is not permitted under the '"
                            + PRODUCTION_PROFILE + "' profile. ADR-003 D8 requires exactly one "
                            + "stat type per deployment — set it to one of " + knownTypes()
                            + " and deploy this image once per type.");
        }

        if (ALL_TYPES.equals(configured)) {
            log.atWarn()
                    .addKeyValue("event", "all_types_mode_enabled")
                    .addKeyValue("configuredStatType", ALL_TYPES)
                    .log(
                            "stats-writer is running in all-types mode. This defeats per-type "
                                    + "prefetch, concurrency and replica tuning and is intended "
                                    + "for local development only (ADR-003 D8)");
        }
    }

    private static boolean isKnownType(String configured) {
        return Arrays.stream(StatType.values())
                .anyMatch(type -> type.wireName().equals(configured));
    }

    private static String knownTypes() {
        return Arrays.stream(StatType.values())
                .map(StatType::wireName)
                .collect(Collectors.joining(", "));
    }
}
