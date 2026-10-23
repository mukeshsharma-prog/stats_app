package com.zenner.datahub.stats.writer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.writer.persistence.WireNumbers.Sign;
import com.zenner.datahub.stats.writer.retry.FailureClassifier;
import com.zenner.datahub.stats.writer.retry.PermanentFailureException;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The one coercion, on every wire shape the five types send: the two
 * halves that were built (lorastats dialect B, sysstats {@code "300"}, omsstats integers)
 * and the two that are not yet ({@code ltestats} signed strings, {@code netstats} nineteen-
 * digit counters), so that their writers have nothing left to write here.
 */
class WireNumbersTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final FailureClassifier classifier = new FailureClassifier();

    private static ObjectNode data(String json) throws Exception {
        return (ObjectNode) JSON.readTree(json);
    }

    // ---- JSON integers: lorastats A/C, omsstats, netstats timespan -------------------

    @Test
    void readsAnIntegerAndItsBigintBoundaries() throws Exception {
        ObjectNode data = data("{\"n\":176,\"max\":9223372036854775807,\"min\":-9223372036854775808}");
        assertThat(WireNumbers.requiredInteger(data, "n")).isEqualTo(176L);
        assertThat(WireNumbers.requiredInteger(data, "max")).isEqualTo(Long.MAX_VALUE);
        assertThat(WireNumbers.requiredInteger(data, "min")).isEqualTo(Long.MIN_VALUE);
    }

    /** JSON Schema's integer has no width; the column does. Checked BEFORE narrowing. */
    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775808", "-9223372036854775809", "99999999999999999999"})
    void refusesAnIntegerWiderThanBigintAsPermanent(String literal) throws Exception {
        ObjectNode data = data("{\"n\":" + literal + "}");
        // Jackson kept the width, which is what makes the check possible.
        assertThat(data.get("n").bigIntegerValue()).isEqualTo(new BigInteger(literal));

        assertThatThrownBy(() -> WireNumbers.requiredInteger(data, "n"))
                .isInstanceOf(PermanentFailureException.class)
                .hasMessage("data.n does not fit a BIGINT column")
                .satisfies(failure -> assertThat(classifier.isPermanent(failure)).isTrue());
    }

    @Test
    void refusesAnIntegerSentAsSomethingElse() throws Exception {
        ObjectNode data = data("{\"s\":\"176\",\"d\":1.5,\"b\":true,\"o\":{}}");
        for (String field : new String[] {"s", "d", "b", "o"}) {
            assertThatThrownBy(() -> WireNumbers.requiredInteger(data, field))
                    .isInstanceOf(PermanentFailureException.class)
                    .hasMessage("data." + field + " must be a BIGINT value");
        }
    }

    @Test
    void anAbsentOrNullIntegerIsNullWhenOptionalAndPermanentWhenRequired() throws Exception {
        ObjectNode data = data("{\"nul\":null}");
        assertThat(WireNumbers.optionalInteger(data, "absent")).isNull();
        assertThat(WireNumbers.optionalInteger(data, "nul")).isNull();
        assertThatThrownBy(() -> WireNumbers.requiredInteger(data, "absent"))
                .isInstanceOf(PermanentFailureException.class)
                .hasMessage("data.absent must be present");
    }

    // ---- digit strings: lorastats B, sysstats timespan, ltestats, netstats ------------

    @Test
    void readsAnUnsignedDigitStringAndItsBigintMaximum() throws Exception {
        ObjectNode data = data("{\"timespan\":\"300\",\"tx\":\"9223372036854775807\",\"zero\":\"0\"}");
        assertThat(WireNumbers.requiredDigitString(data, "timespan", Sign.UNSIGNED)).isEqualTo(300L);
        // netstats: a nineteen-digit counter at the exact column maximum.
        assertThat(WireNumbers.requiredDigitString(data, "tx", Sign.UNSIGNED)).isEqualTo(Long.MAX_VALUE);
        assertThat(WireNumbers.requiredDigitString(data, "zero", Sign.UNSIGNED)).isZero();
    }

    /** ltestats: rssi/rsrp/rsrq are signed strings, and the minimum is a legal value. */
    @Test
    void readsASignedDigitStringAndItsBigintMinimum() throws Exception {
        ObjectNode data = data("{\"rssi\":\"-97\",\"rsrp\":\"-9223372036854775808\",\"rsrq\":\"-0\"}");
        assertThat(WireNumbers.requiredDigitString(data, "rssi", Sign.SIGNED)).isEqualTo(-97L);
        assertThat(WireNumbers.requiredDigitString(data, "rsrp", Sign.SIGNED)).isEqualTo(Long.MIN_VALUE);
        assertThat(WireNumbers.requiredDigitString(data, "rsrq", Sign.SIGNED)).isZero();
    }

    /**
     * Nineteen digits pass every schema pattern and still do not fit: the gap no regex
     * can express, checked against BigInteger before the narrowing conversion.
     */
    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775808", "9999999999999999999"})
    void refusesAnUnsignedDigitStringOneAboveBigintAsPermanent(String text) throws Exception {
        ObjectNode data = data("{\"tx\":\"" + text + "\"}");
        assertThatThrownBy(() -> WireNumbers.requiredDigitString(data, "tx", Sign.UNSIGNED))
                .isInstanceOf(PermanentFailureException.class)
                .hasMessage("data.tx does not fit a BIGINT column")
                .satisfies(failure -> assertThat(classifier.isPermanent(failure)).isTrue());
    }

    @Test
    void refusesASignedDigitStringOneBelowBigint() throws Exception {
        ObjectNode data = data("{\"rsrp\":\"-9223372036854775809\"}");
        assertThatThrownBy(() -> WireNumbers.requiredDigitString(data, "rsrp", Sign.SIGNED))
                .isInstanceOf(PermanentFailureException.class)
                .hasMessage("data.rsrp does not fit a BIGINT column");
    }

    /** An unsigned member with a minus is refused as a sign error, not silently accepted. */
    @Test
    void refusesANegativeStringWhereTheSchemaAdmitsNone() throws Exception {
        ObjectNode data = data("{\"timespan\":\"-300\"}");
        assertThatThrownBy(() -> WireNumbers.requiredDigitString(data, "timespan", Sign.UNSIGNED))
                .isInstanceOf(PermanentFailureException.class)
                .hasMessage("data.timespan must not be negative");
    }

    /**
     * Shapes the JDK would parse and the gate never admits. A mapping wider than its gate
     * is a second contract nobody reviewed.
     */
    @ParameterizedTest
    @ValueSource(strings = {"+5", " 5", "5 ", "1e3", "1.0", "0x1F", "", "-", "five", "١٢٣"})
    void refusesAnythingThatIsNotAnOptionalMinusAndDigits(String text) throws Exception {
        ObjectNode data = data(JSON.writeValueAsString(java.util.Map.of("n", text)));
        assertThatThrownBy(() -> WireNumbers.requiredDigitString(data, "n", Sign.SIGNED))
                .isInstanceOf(PermanentFailureException.class)
                .hasMessage("data.n is not an integral value");
    }

    /** A number where the wire says string is a mapping failure, never a silent cast. */
    @Test
    void refusesADigitStringSentAsANumber() throws Exception {
        ObjectNode data = data("{\"timespan\":300}");
        assertThatThrownBy(() -> WireNumbers.requiredDigitString(data, "timespan", Sign.UNSIGNED))
                .isInstanceOf(PermanentFailureException.class)
                .hasMessage("data.timespan must be a digit string");
    }

    @Test
    void anAbsentOrNullDigitStringIsNullWhenOptionalAndPermanentWhenRequired() throws Exception {
        ObjectNode data = data("{\"nul\":null}");
        assertThat(WireNumbers.optionalDigitString(data, "absent", Sign.UNSIGNED)).isNull();
        assertThat(WireNumbers.optionalDigitString(data, "nul", Sign.UNSIGNED)).isNull();
        assertThatThrownBy(() -> WireNumbers.requiredDigitString(data, "absent", Sign.UNSIGNED))
                .isInstanceOf(PermanentFailureException.class)
                .hasMessage("data.absent must be present");
    }

    /** Guard P2: a refusal names the field and never echoes the value. */
    @Test
    void noRefusalEverCarriesThePayloadValue() throws Exception {
        String secret = "9223372036854775808";
        ObjectNode data = data("{\"i\":" + secret + ",\"s\":\"" + secret + "\",\"t\":\"five\"}");
        for (Runnable read :
                new Runnable[] {
                    () -> WireNumbers.requiredInteger(data, "i"),
                    () -> WireNumbers.requiredDigitString(data, "s", Sign.UNSIGNED),
                    () -> WireNumbers.requiredDigitString(data, "t", Sign.UNSIGNED)
                }) {
            assertThatThrownBy(read::run)
                    .isInstanceOf(PermanentFailureException.class)
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .doesNotContain(secret)
                            .doesNotContain("five"));
        }
    }

    @Test
    void theBareParseIsTheSameRuleTheNodeReadersUse() {
        assertThat(WireNumbers.parseDigitString("f", "176", Sign.UNSIGNED)).isEqualTo(176L);
        assertThatThrownBy(() -> WireNumbers.parseDigitString("f", "9223372036854775808", Sign.UNSIGNED))
                .isInstanceOf(PermanentFailureException.class);
        assertThatThrownBy(() -> WireNumbers.parseDigitString("f", null, Sign.UNSIGNED))
                .isInstanceOf(PermanentFailureException.class)
                .hasMessage("data.f is not an integral value");
    }
}
