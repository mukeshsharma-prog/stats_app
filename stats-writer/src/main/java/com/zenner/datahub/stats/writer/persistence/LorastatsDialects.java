package com.zenner.datahub.stats.writer.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.writer.retry.PermanentFailureException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Normalises the three production payload dialects into one shape (T-063; DL-105,
 * ADR-007 §4 as amended, STA-14A, STA-04C).
 *
 * <h2>The three dialects</h2>
 *
 * <table>
 *   <caption>Observed in three consecutive production records, 29 August 2026</caption>
 *   <tr><th>Dialect</th><th>Names</th><th>Values</th></tr>
 *   <tr><td>A</td><td>bare</td><td>integer — {@code "rx_nr_pkts": 176}</td></tr>
 *   <tr><td>B</td><td>bare</td><td>string — {@code "rx_nr_pkts": "176"}</td></tr>
 *   <tr><td>C</td><td>{@code lora_}-prefixed</td><td>integer — {@code "lora_rx_nr_pkts": 176}</td></tr>
 * </table>
 *
 * <h2>Four rules, and each one is load-bearing</h2>
 *
 * <ol>
 *   <li><strong>Detection is structural.</strong> Which required key-set is present, and
 *       what its values are. Every one of the three carries {@code 1} in the envelope's
 *       record marker, so nothing on the record discriminates them — and since DL-105
 *       this class sits immediately beside that field looking exactly like the
 *       discriminator it is not (trap 20). The failure that would cause is silent: a
 *       parser selected by that field works perfectly on whichever dialect its author
 *       tested and mis-parses the other two into the same typed columns, so the rows are
 *       <em>wrong</em> rather than absent and no test fails.</li>
 *   <li><strong>One stored shape.</strong> All three normalise to the same bare names and
 *       the same typed columns. The dialect a record arrived in is a metric label, not a
 *       column: storing it would cost a discriminator and give the API three shapes for
 *       one stat type, for a distinction with no business meaning.</li>
 *   <li><strong>Coercion is explicit and range-checked before conversion.</strong> A
 *       dialect-B string that does not parse, or that will not fit the signed
 *       {@code BIGINT} column, dead-letters. It is never zeroed, truncated or defaulted —
 *       that is the {@code int4}-wrapping shape of trap 11, and it produces a row that is
 *       wrong rather than missing.</li>
 *   <li><strong>The enumeration is closed at three.</strong> "All the known dialects" is
 *       exactly three. A fourth spelling matches nothing here and dead-letters, so it
 *       surfaces in a queue a human reads instead of being absorbed into a union that
 *       only ever grows — the half of DL-089 that survives Q15a.</li>
 * </ol>
 *
 * <h2>Its relationship to the schema</h2>
 *
 * <p>{@code docs/schemas/lorastats-v1.schema.json} is the gate; this is the mapping. The
 * schema's {@code oneOf} has already refused anything that is not one of the three by the
 * time a record arrives here, and the detection below re-derives the same answer from the
 * same evidence. They are two expressions of one decision, and where they disagree this
 * class throws rather than guessing — a record neither of them can place belongs on the
 * DLQ, not in a column chosen by a fallback.</p>
 */
public final class LorastatsDialects {

    /** The prefix dialect C puts on its counters — and only on its counters. */
    private static final String PREFIX = "lora_";

    /**
     * The counters every dialect must carry. Also the detection evidence: which of these
     * are present, and under which spelling, is the entire structural signal.
     */
    static final List<String> REQUIRED_FIELDS =
            List.of(
                    "rx_nr_pkts",
                    "rx_crc_missing",
                    "rx_crc_bad",
                    "tx_success",
                    "tx_failed",
                    "tx_peek",
                    "tx_peek_index_zero",
                    "tx_override",
                    "tx_busy",
                    "tx_dequeue_error",
                    "tx_type_beacon",
                    "chip_comm_error",
                    "util_fopen_failed");

    /**
     * Observed in production but not on every record — which is why they are optional
     * rather than an oversight. No single production record carries all four.
     */
    static final List<String> OPTIONAL_FIELDS =
            List.of("rx_short_pkt", "fsk_received", "rx_type_arol", "timespan");

    /**
     * Every {@code data} member that has a typed column, in canonical (bare) spelling.
     * {@code LorastatsColumnMappingTest} derives the same set from the normative schema,
     * so a member added to one and not the other is a build failure rather than a column
     * that is silently always null.
     */
    static final Set<String> CANONICAL_FIELDS = canonicalFields();

    private LorastatsDialects() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** Which of the three known dialects a record was written in. */
    public enum Dialect {
        BARE_INTEGER("a_bare_integer"),
        BARE_STRING("b_bare_string"),
        PREFIXED_INTEGER("c_prefixed_integer");

        private final String label;

        Dialect(String label) {
            this.label = label;
        }

        /**
         * The metric tag value. Lettered so the three sort into the order DL-105
         * documents them in, which is the order an operator reading that decision will
         * look for them in.
         */
        public String label() {
            return label;
        }
    }

    /**
     * One payload in canonical form: bare names, integral values, and every member the
     * record carried — including the ones with no column, which the caller preserves in
     * {@code data_extra} (D14b, DL-095).
     */
    public record Normalised(Dialect dialect, ObjectNode data) {

        public Normalised {
            Objects.requireNonNull(dialect, "dialect");
            Objects.requireNonNull(data, "data");
        }
    }

    /**
     * Detects the dialect and rewrites the payload into canonical form.
     *
     * @throws PermanentFailureException when the payload matches no known dialect,
     *     matches more than one, mixes value types within one dialect, carries two
     *     spellings of the same counter, or holds a string counter that does not parse or
     *     will not fit a {@code BIGINT}. Every one of those recurs identically on every
     *     future attempt, so the delivery skips the retry tiers and dead-letters.
     */
    public static Normalised normalise(JsonNode data) {
        if (data == null || !data.isObject()) {
            throw new PermanentFailureException("data must be a JSON object");
        }

        boolean bare = carriesEveryRequiredField(data, "");
        boolean prefixed = carriesEveryRequiredField(data, PREFIX);

        final Dialect dialect;
        if (bare && prefixed) {
            // Both spellings of the full required set. The record is ambiguous, and
            // picking either one would silently discard thirteen counters.
            throw new PermanentFailureException("data matches more than one known dialect");
        } else if (bare) {
            dialect = valueShapeOf(data);
        } else if (prefixed) {
            dialect = Dialect.PREFIXED_INTEGER;
        } else {
            // Rule 4. A fourth spelling belongs on the DLQ, where a person sees it.
            throw new PermanentFailureException("data matches no known payload dialect");
        }

        return new Normalised(dialect, canonicalise(data, dialect));
    }

    private static boolean carriesEveryRequiredField(JsonNode data, String prefix) {
        for (String field : REQUIRED_FIELDS) {
            JsonNode value = data.get(prefix + field);
            if (value == null || value.isNull()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Dialect A and dialect B differ only in how their values are written, so the value
     * type of the required set is the whole signal. A payload that mixes the two is not
     * one of the three known dialects: a dialect is internally consistent by definition,
     * and absorbing a half-and-half record would mean guessing per field.
     */
    private static Dialect valueShapeOf(JsonNode data) {
        boolean everyValueIsText = true;
        boolean everyValueIsIntegral = true;
        for (String field : REQUIRED_FIELDS) {
            JsonNode value = data.get(field);
            everyValueIsText &= value.isTextual();
            everyValueIsIntegral &= value.isIntegralNumber();
        }
        if (everyValueIsIntegral) {
            return Dialect.BARE_INTEGER;
        }
        if (everyValueIsText) {
            return Dialect.BARE_STRING;
        }
        throw new PermanentFailureException("data mixes counter value types within one dialect");
    }

    private static ObjectNode canonicalise(JsonNode data, Dialect dialect) {
        ObjectNode canonical = new ObjectNode(JsonNodeFactory.instance);
        for (Map.Entry<String, JsonNode> member : data.properties()) {
            String name = canonicalNameOf(member.getKey(), dialect);
            JsonNode value = member.getValue();

            if (CANONICAL_FIELDS.contains(name) && value.isTextual()) {
                value = coerce(name, value.textValue());
            }
            if (canonical.has(name)) {
                // e.g. both lora_timespan and timespan. One of them would be dropped,
                // and there is no basis for choosing which.
                throw new PermanentFailureException("data carries two spellings of one member");
            }
            canonical.set(name, value);
        }
        return canonical;
    }

    /**
     * Strips {@code lora_} only from members that name a known counter, and only in the
     * dialect that uses it. An unrecognised {@code lora_}-prefixed member keeps its
     * spelling and is preserved verbatim in {@code data_extra}: renaming an unknown field
     * on the way to a column that does not exist would be inventing a mapping.
     */
    private static String canonicalNameOf(String name, Dialect dialect) {
        if (dialect != Dialect.PREFIXED_INTEGER || !name.startsWith(PREFIX)) {
            return name;
        }
        String stripped = name.substring(PREFIX.length());
        return CANONICAL_FIELDS.contains(stripped) ? stripped : name;
    }

    /**
     * Rule 3, and the order of the two steps is the rule — the rule itself lives in
     * {@link WireNumbers} since T-062, shared with every writer, so that the range check
     * cannot be present here and missing there. A dialect-B counter is an
     * <em>unsigned</em> digit string (the schema's pattern admits no minus), and the
     * check is made against {@code BigInteger} before the narrowing conversion.
     */
    private static JsonNode coerce(String field, String text) {
        return LongNode.valueOf(WireNumbers.parseDigitString(field, text, WireNumbers.Sign.UNSIGNED));
    }

    private static Set<String> canonicalFields() {
        Set<String> fields = new LinkedHashSet<>(REQUIRED_FIELDS);
        fields.addAll(OPTIONAL_FIELDS);
        return Set.copyOf(fields);
    }
}
