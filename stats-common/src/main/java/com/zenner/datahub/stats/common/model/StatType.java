package com.zenner.datahub.stats.common.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The approved V1 statistic types.
 *
 * <p>The wire name is also the routing key, queue infix, schema filename prefix,
 * and table name. Keeping that literal here prevents those identities from
 * drifting independently.</p>
 */
public enum StatType {
    LORASTATS("lorastats"),
    LTESTATS("ltestats"),
    SYSSTATS("sysstats"),
    NETSTATS("netstats"),
    OMSSTATS("omsstats");

    private static final Map<String, StatType> BY_WIRE_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(StatType::wireName, Function.identity()));

    private final String wireName;

    StatType(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static StatType fromWireName(String wireName) {
        StatType type = BY_WIRE_NAME.get(wireName);
        if (type != null) {
            return type;
        }
        // Do not include the attacker-controlled literal in this exception. The
        // ingress error mapper turns this into safe Problem Details.
        throw new IllegalArgumentException("Unknown stat type literal");
    }
}
