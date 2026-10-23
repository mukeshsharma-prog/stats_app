package com.zenner.datahub.stats.writer.consumer;

import com.zenner.datahub.stats.common.model.StatType;
import java.util.Objects;
import org.springframework.core.env.Environment;

/**
 * The one reading of {@code stats.writer.stat-type} that every per-type condition shares.
 *
 * <p>Held here rather than on the lorastats condition so that a sibling type's condition
 * does not import a constant from another type's file — the second type is the moment
 * that dependency direction would have been set for all five.</p>
 */
public final class StatTypeDeployment {

    public static final String STAT_TYPE_PROPERTY = "stats.writer.stat-type";
    public static final String ALL_TYPES = "all";

    /**
     * The start of every consumer's {@code @RabbitListener(autoStartup = …)} expression;
     * each consumer appends only its wire name and {@link #AUTO_STARTUP_SUFFIX}.
     */
    public static final String AUTO_STARTUP_PREFIX = "#{@statsMessageProcessorAvailability.available('";

    /**
     * Closes the expression and applies Spring's own listener switch to EVERY type alike.
     * An explicit {@code autoStartup} on {@code @RabbitListener} overrides the
     * container factory, so {@code spring.rabbitmq.listener.simple.auto-startup} reaches a
     * listener only if its expression names it. Once, two consumers named it and
     * three did not: {@code false} silently stopped two of five types. Held once here so
     * the five consumers cannot drift apart again; {@code ConsumerAutoStartupIsUniformTest}
     * checks every listener against it.
     */
    public static final String AUTO_STARTUP_SUFFIX =
            "') && ${spring.rabbitmq.listener.simple.auto-startup:true}}";

    private StatTypeDeployment() {
        throw new AssertionError("Holder must not be instantiated");
    }

    /** Whether this deployment serves {@code type}: its own name, or the development {@code all}. */
    public static boolean serves(Environment environment, StatType type) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(type, "type");
        String configured = environment.getProperty(STAT_TYPE_PROPERTY, ALL_TYPES).trim();
        return ALL_TYPES.equals(configured) || type.wireName().equals(configured);
    }
}
