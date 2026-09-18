package com.zenner.datahub.stats.writer.consumer;

import com.zenner.datahub.stats.common.model.StatType;
import java.util.Objects;
import org.springframework.core.env.Environment;

/**
 * The one reading of {@code stats.writer.stat-type} that every per-type condition shares
 * (ADR-003 D8; T-035, DL-148).
 *
 * <p>Held here rather than on the lorastats condition so that a sibling type's condition
 * does not import a constant from another type's file — the second type is the moment
 * that dependency direction would have been set for all five.</p>
 */
public final class StatTypeDeployment {

    public static final String STAT_TYPE_PROPERTY = "stats.writer.stat-type";
    public static final String ALL_TYPES = "all";

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
