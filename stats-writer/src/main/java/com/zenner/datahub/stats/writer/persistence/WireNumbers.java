package com.zenner.datahub.stats.writer.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.zenner.datahub.stats.writer.retry.PermanentFailureException;
import java.math.BigInteger;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The one range-checked coercion from a wire number to a {@code BIGINT} column (T-062;
 * STA-04C, ADR-003 D2, ADR-007 §6, DL-105 rule 3, DL-126).
 *
 * <p>There is no single wire type for "a number" in this system: {@code timespan} is an
 * integer in {@code lorastats}/{@code omsstats}/{@code netstats} and the string
 * {@code "300"} in {@code sysstats}; every {@code ltestats} numeric is a string, some of
 * them signed; {@code netstats} counters are digit strings up to nineteen digits;
 * {@code lorastats} dialect B is dialect A with every counter quoted. The parse is per
 * type — each writer says which members are integers and which are digit strings — but
 * the <em>check</em> is one rule, and it lives here so that it cannot go missing from one
 * writer while present in another. Before this class there were three private copies
 * written weeks apart, and they disagreed: one threw an exception the failure classifier
 * treats as transient, so an overflow on that type burned three retry tiers before it
 * reached the DLQ, and one accepted a negative string the schema forbids.</p>
 *
 * <h2>The rule, and why its order is the rule</h2>
 *
 * <p>The schemas cap a digit string at nineteen digits; the signed {@code BIGINT} maximum
 * ({@code 9223372036854775807}) is also nineteen digits, and JSON Schema's {@code integer}
 * has no width at all. The gap between "what the gate admits" and "what the column holds"
 * is not expressible as a regex, so it is held here — and it is checked against
 * {@link BigInteger} <strong>before</strong> any narrowing conversion, because a conversion
 * that has already overflowed has nothing left to test: the value is wrong and the
 * arithmetic that made it wrong reported nothing. A value outside the column is refused,
 * never zeroed, wrapped, truncated or defaulted — a row that is wrong is worse than a row
 * that is absent, because no test and no counter will ever see it.</p>
 *
 * <p>Every refusal is a {@link PermanentFailureException}: the bytes will not change on
 * redelivery, so the record is {@code unmappable} on its first attempt (DL-126) and skips
 * the retry tiers. Messages name the <em>field</em>, never the value (guard P2, DL-124).</p>
 */
public final class WireNumbers {

    /** Which sign a digit string may carry — the schema's pattern says, the caller repeats it. */
    public enum Sign {
        /** {@code ^(0|[1-9][0-9]{0,18})$}: counters, timespans, uptimes. */
        UNSIGNED,
        /** {@code ^-?(0|[1-9][0-9]{0,18})$}: {@code ltestats} signal levels such as {@code "-97"}. */
        SIGNED
    }

    private static final BigInteger BIGINT_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger BIGINT_MIN = BigInteger.valueOf(Long.MIN_VALUE);

    /**
     * The only spelling a digit string may take: an optional minus and digits. A leading
     * {@code +}, whitespace, an exponent or a decimal point would all parse somewhere in
     * the JDK and are refused here on purpose — the gate's patterns admit none of them,
     * and a mapping wider than its gate is a second, unreviewed contract.
     */
    private static final Pattern DIGIT_STRING = Pattern.compile("-?[0-9]+");

    private WireNumbers() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    // ---- JSON integers ----------------------------------------------------------------

    /** A required member sent as a JSON integer. */
    public static long requiredInteger(JsonNode data, String field) {
        Long value = optionalInteger(data, field);
        if (value == null) {
            throw new PermanentFailureException("data." + field + " must be present");
        }
        return value;
    }

    /**
     * An optional member sent as a JSON integer, or {@code null} when absent or JSON
     * {@code null}. Jackson holds a literal wider than 64 bits as a {@code BigInteger}
     * node and {@link JsonNode#canConvertToLong()} says so — that is the range check,
     * made before {@link JsonNode#longValue()} could silently narrow it.
     */
    public static Long optionalInteger(JsonNode data, String field) {
        JsonNode value = member(data, field);
        if (value == null) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw new PermanentFailureException("data." + field + " must be a BIGINT value");
        }
        if (!value.canConvertToLong()) {
            throw new PermanentFailureException("data." + field + " does not fit a BIGINT column");
        }
        return value.longValue();
    }

    // ---- digit strings ----------------------------------------------------------------

    /** A required member sent as a digit string. */
    public static long requiredDigitString(JsonNode data, String field, Sign sign) {
        Long value = optionalDigitString(data, field, sign);
        if (value == null) {
            throw new PermanentFailureException("data." + field + " must be present");
        }
        return value;
    }

    /** An optional member sent as a digit string, or {@code null} when absent or JSON {@code null}. */
    public static Long optionalDigitString(JsonNode data, String field, Sign sign) {
        JsonNode value = member(data, field);
        if (value == null) {
            return null;
        }
        if (!value.isTextual()) {
            throw new PermanentFailureException("data." + field + " must be a digit string");
        }
        return parseDigitString(field, value.textValue(), sign);
    }

    /**
     * The digit-string rule on a bare value, for callers that already hold the text —
     * {@link LorastatsDialects} coerces dialect B while it is still normalising names.
     */
    public static long parseDigitString(String field, String text, Sign sign) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(sign, "sign");
        if (text == null || !DIGIT_STRING.matcher(text).matches()) {
            throw new PermanentFailureException("data." + field + " is not an integral value");
        }
        if (sign == Sign.UNSIGNED && text.charAt(0) == '-') {
            throw new PermanentFailureException("data." + field + " must not be negative");
        }
        BigInteger parsed = new BigInteger(text);
        if (parsed.compareTo(BIGINT_MAX) > 0 || parsed.compareTo(BIGINT_MIN) < 0) {
            throw new PermanentFailureException("data." + field + " does not fit a BIGINT column");
        }
        // Exact: the comparison above is what makes it exact, not a property of longValue().
        return parsed.longValue();
    }

    private static JsonNode member(JsonNode data, String field) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(field, "field");
        JsonNode value = data.get(field);
        return value == null || value.isNull() ? null : value;
    }
}
