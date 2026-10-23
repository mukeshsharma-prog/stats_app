package com.zenner.datahub.stats.writer.retention;

import com.zenner.datahub.stats.common.model.StatType;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;

/**
 * The one place a daily partition's name, bound expression and quoted identifier are
 * spelled.
 *
 * <p><strong>Why this is shared rather than written twice.</strong> Pre-creation composes
 * these names and retention has to recognise them again — and the two directions are only
 * safe while they agree exactly. A retention run that derived the day from a slightly
 * different pattern would either skip a partition it should drop, which is invisible until
 * the disk fills, or fail to match the bound of one it should keep. Both halves live here
 * so there is no second spelling to drift from.</p>
 *
 * <p>Nothing here names an individual stat type: every identifier derives from
 * {@link StatType#wireName()}, which is what keeps a new type additive
 * ({@code StatTypeFanOutTest}).</p>
 */
final class PartitionNaming {

    /**
     * Underscores rather than hyphens because the result is a bare SQL identifier, and
     * {@code uuuu} rather than {@code yyyy} because {@code yyyy} is era-relative and
     * parses a nonsense year without complaining under {@code STRICT} resolution.
     */
    private static final DateTimeFormatter PARTITION_DATE =
            DateTimeFormatter.ofPattern("uuuu_MM_dd");

    private PartitionNaming() {}

    /** The child table name for {@code type}'s partition covering {@code day} (UTC). */
    static String partitionName(StatType type, LocalDate day) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(day, "day");
        return type.wireName() + "_" + PARTITION_DATE.format(day);
    }

    /**
     * The UTC day a child table covers, or empty when the name is not one this module
     * produced.
     *
     * <p>Empty is a <strong>refusal to act</strong>, not an inconvenience. Retention drops
     * tables, and a child whose name it cannot account for is one a human created for a
     * reason nobody recorded. Returning empty is what makes "we only ever drop partitions
     * we can name, date and bound-check" true by construction.</p>
     */
    static Optional<LocalDate> dayOf(StatType type, String relationName) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(relationName, "relationName");
        String prefix = type.wireName() + "_";
        if (!relationName.startsWith(prefix)) {
            return Optional.empty();
        }
        String suffix = relationName.substring(prefix.length());
        try {
            LocalDate day = LocalDate.parse(suffix, PARTITION_DATE);
            // Round-trip the parse. Without it, a name whose components are numerically
            // valid but not canonically formatted -- lorastats_2026_1_1 -- would be
            // accepted here and then compared against a bound this module never wrote.
            return PARTITION_DATE.format(day).equals(suffix) ? Optional.of(day) : Optional.empty();
        } catch (DateTimeParseException notOurs) {
            return Optional.empty();
        }
    }

    /**
     * The exact {@code FOR VALUES} clause for {@code day}, half-open on the right so
     * consecutive days neither overlap nor leave a gap.
     *
     * <p>Rendered with an explicit {@code +00} offset and compared against
     * {@code pg_get_expr}, which renders {@code timestamptz} in the <em>session</em> time
     * zone — so every caller must set the session to UTC first, or a correct partition
     * reads back as one with the wrong bounds. {@link PartitionSession} owns that.</p>
     */
    static String partitionBound(LocalDate day) {
        Objects.requireNonNull(day, "day");
        return "FOR VALUES FROM ('"
                + day
                + " 00:00:00+00') TO ('"
                + day.plusDays(1)
                + " 00:00:00+00')";
    }

    /**
     * Quotes an identifier for interpolation into DDL.
     *
     * <p>PostgreSQL takes no bind parameter for a table name, so the name has to be
     * concatenated. Everything reaching here originates in the closed {@link StatType}
     * enum and a {@link LocalDate}; the doubling is the belt to that braces.</p>
     */
    static String quotedIdentifier(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
