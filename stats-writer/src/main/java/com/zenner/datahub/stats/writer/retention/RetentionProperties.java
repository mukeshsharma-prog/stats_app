package com.zenner.datahub.stats.writer.retention;

import com.zenner.datahub.stats.common.model.StatType;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

/**
 * Partition maintenance and retention settings — {@code stats.retention}.
 *
 * <h2>Nothing here has a default in code</h2>
 *
 * <p>Every component is required, and {@code default-days} is the reason. Retention
 * <em>deletes</em> data, so a deployment that forgets to configure it must fail to start
 * rather than silently adopt a window somebody guessed. That is the same reasoning that
 * leaves {@code stats.input.auth.mode} with no default: for a control whose wrong value is
 * invisible, absent-and-loud beats present-and-plausible. The decided value — 90 days —
 * lives in {@code application.properties} where an operator can read it, not in a
 * constant where they cannot.</p>
 *
 * <h2>Why per-type overrides are a map</h2>
 *
 * <p>An earlier shape was {@code stats.retention.<type>-days}, one flat key
 * per type. It is not used, because a mistyped flat key binds to nothing and reports nothing, and a retention
 * window that silently reverted to the default is exactly the class of failure this
 * project keeps finding. {@code stats.retention.days-by-type.<wire-name>} is validated
 * against {@link StatType#wireName()} here, so a typo fails the context rather than
 * quietly changing how long a type's data is kept.</p>
 */
@ConfigurationProperties(prefix = "stats.retention")
public record RetentionProperties(
        PartitionGranularity partitionGranularity,
        int preCreateDays,
        String schedule,
        Integer defaultDays,
        Map<String, Integer> daysByType,
        Duration stagger) {

    /** Safety bound against accidentally issuing an unbounded series of DDL statements. */
    public static final int MAX_PRE_CREATE_DAYS = 366;

    /**
     * Ten years. Over-retention is the safe direction, so this is not a policy limit —
     * it bounds the cutoff arithmetic so a mistyped window cannot walk off the end of
     * {@link java.time.LocalDate} inside a scheduled job.
     */
    public static final int MAX_RETENTION_DAYS = 3650;

    /**
     * The longest span of daily partitions one maintenance pass may ensure: the whole
     * retention window behind today plus the pre-creation horizon ahead of it.
     * Not a policy limit — the two bounds it is built from already are — it exists so
     * {@link PartitionPrecreator} can refuse an absurd range before issuing any DDL.
     */
    public static final int MAX_ENSURED_DAYS = MAX_RETENTION_DAYS + MAX_PRE_CREATE_DAYS;

    /**
     * The stagger offsets one stat type's maintenance from the next, so the five types do
     * not fire together. It is capped because the offset
     * is taken by waiting, and a run that waits an hour before dropping a partition is
     * no longer an off-peak job.
     */
    public static final Duration MAX_STAGGER = Duration.ofMinutes(10);

    public RetentionProperties {
        Objects.requireNonNull(partitionGranularity, "stats.retention.partition-granularity");
        if (partitionGranularity != PartitionGranularity.DAILY) {
            throw new IllegalArgumentException(
                    "Only daily partition granularity is supported");
        }
        if (preCreateDays < 0) {
            throw new IllegalArgumentException("preCreateDays must be zero or greater");
        }
        if (preCreateDays > MAX_PRE_CREATE_DAYS) {
            throw new IllegalArgumentException(
                    "preCreateDays must not exceed " + MAX_PRE_CREATE_DAYS);
        }
        if (schedule == null
                || (!Scheduled.CRON_DISABLED.equals(schedule)
                        && !CronExpression.isValidExpression(schedule))) {
            throw new IllegalArgumentException("schedule must be a valid Spring cron expression");
        }
        if (defaultDays == null) {
            throw new IllegalArgumentException(
                    "stats.retention.default-days must be set — retention deletes data and "
                            + "has no default in code");
        }
        requireRetentionDays(defaultDays, "stats.retention.default-days");
        daysByType = normaliseOverrides(daysByType);
        Objects.requireNonNull(stagger, "stats.retention.stagger");
        if (stagger.isNegative()) {
            throw new IllegalArgumentException("stats.retention.stagger must not be negative");
        }
        if (stagger.compareTo(MAX_STAGGER) > 0) {
            throw new IllegalArgumentException(
                    "stats.retention.stagger must not exceed " + MAX_STAGGER);
        }
    }

    /**
     * How many days of {@code ts.device} history {@code type} keeps.
     *
     * <p>Resolved here rather than at each call site so the guard that skips a late record
     * and the sweep that drops its partition cannot end up reading different keys —
     * which would admit a record into a partition that no longer exists.</p>
     */
    public int retentionDaysFor(StatType type) {
        Objects.requireNonNull(type, "type");
        return daysByType.getOrDefault(type.wireName(), defaultDays);
    }

    /** The offset applied before {@code type}'s slot in a maintenance run. */
    public Duration staggerFor(StatType type) {
        Objects.requireNonNull(type, "type");
        return stagger.multipliedBy(type.ordinal());
    }

    private static Map<String, Integer> normaliseOverrides(Map<String, Integer> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return Map.of();
        }
        // An EnumMap round trip is what rejects an unknown key: StatType.fromWireName
        // throws on anything that is not one of the five, so `stats.retention
        // .days-by-type.lorstats=30` fails the context instead of being ignored.
        Map<StatType, Integer> byType = new EnumMap<>(StatType.class);
        overrides.forEach(
                (wireName, days) -> {
                    StatType type = resolve(wireName);
                    requireRetentionDays(days, "stats.retention.days-by-type." + wireName);
                    byType.put(type, days);
                });
        Map<String, Integer> normalised = new LinkedHashMap<>();
        byType.forEach((type, days) -> normalised.put(type.wireName(), days));
        return Map.copyOf(normalised);
    }

    private static StatType resolve(String wireName) {
        try {
            return StatType.fromWireName(wireName);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "stats.retention.days-by-type."
                            + wireName
                            + " does not name a known stat type; a per-type retention window "
                            + "that binds to nothing would silently fall back to the default",
                    unknown);
        }
    }

    private static void requireRetentionDays(Integer days, String property) {
        if (days == null) {
            throw new IllegalArgumentException(property + " must be set");
        }
        if (days < 1) {
            // Zero would make today's own partition eligible for dropping the moment it
            // was created, which is not "keep nothing" but "fight pre-creation forever".
            throw new IllegalArgumentException(property + " must be at least 1 day");
        }
        if (days > MAX_RETENTION_DAYS) {
            throw new IllegalArgumentException(
                    property + " must not exceed " + MAX_RETENTION_DAYS + " days");
        }
    }

    public enum PartitionGranularity {
        DAILY
    }
}
