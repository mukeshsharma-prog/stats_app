package com.zenner.datahub.stats.writer.validation;

import com.zenner.datahub.stats.common.model.StatsEnvelope;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One delivery that has passed the Writer's payload gate, with the schema that
 * admitted it.
 *
 * <p>The schema label travels with the record rather than being re-derived at the
 * INSERT because the {@code schema_version} column is supposed to say
 * <em>which of our contracts admitted this row</em>. A constant compiled into the
 * writer answers a different question — "which contract did the author believe was
 * current" — and the two only agree until the day a second schema exists, at which
 * point every row already stored claims the wrong one and nothing fails.</p>
 *
 * <p>This type is what makes {@link com.zenner.datahub.stats.writer.persistence.StatsBatchWriter}
 * take validated input by construction. A writer that accepted a bare envelope could
 * be called with an unvalidated one, and the gate would become a convention rather
 * than a signature.</p>
 */
public record ValidatedRecord(StatsEnvelope envelope, short schemaVersion) {

    /** The catalogue's own label, e.g. {@code v1}, taken from each schema's {@code $id}. */
    private static final Pattern SCHEMA_LABEL = Pattern.compile("^v([0-9]{1,4})$");

    public ValidatedRecord {
        Objects.requireNonNull(envelope, "envelope");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be one or greater");
        }
    }

    /**
     * Converts the validator's {@code v1}-style label into the {@code smallint} the
     * column holds. A label that is not {@code v<number>} throws rather than
     * defaulting: a silently defaulted schema label is a row that claims to have been
     * admitted by a contract nobody checked.
     */
    public static ValidatedRecord of(StatsEnvelope envelope, String schemaLabel) {
        Objects.requireNonNull(schemaLabel, "schemaLabel");
        Matcher matcher = SCHEMA_LABEL.matcher(schemaLabel);
        if (!matcher.matches()) {
            throw new IllegalStateException("Schema label is not v<number>");
        }
        int parsed = Integer.parseInt(matcher.group(1));
        if (parsed > Short.MAX_VALUE) {
            throw new IllegalStateException("Schema label does not fit the schema_version column");
        }
        return new ValidatedRecord(envelope, (short) parsed);
    }
}
