package com.zenner.datahub.stats.writer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.writer.persistence.LorastatsDialects.Dialect;
import com.zenner.datahub.stats.writer.persistence.LorastatsDialects.Normalised;
import com.zenner.datahub.stats.writer.retry.PermanentFailureException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T-063 / DL-105 — three dialects in, one shape out, and a closed enumeration.
 *
 * <p>The three accepted cases are driven from the NORMATIVE fixtures rather than from
 * hand-typed payloads. Two of them are unmodified production records of 29 August 2026,
 * and the property that matters about them — that dialect C mixes prefixed counters with
 * a bare {@code fsk_received} and a bare {@code timespan} — is exactly the detail a
 * retyped copy loses.</p>
 */
class LorastatsDialectsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void readsDialectAWithoutChangingAnything() {
        Normalised normalised = LorastatsDialects.normalise(payload("valid/lorastats-production-canonical.json"));

        assertThat(normalised.dialect()).isEqualTo(Dialect.BARE_INTEGER);
        assertThat(normalised.data().path("rx_nr_pkts").isIntegralNumber()).isTrue();
    }

    @Test
    void coercesDialectBStringsToIntegralValues() {
        Normalised normalised = LorastatsDialects.normalise(payload("valid/lorastats-dialect-string-values.json"));

        assertThat(normalised.dialect()).isEqualTo(Dialect.BARE_STRING);
        assertThat(normalised.data().path("rx_nr_pkts").isIntegralNumber())
                .as("a coerced counter is a NUMBER, not a string that happens to look numeric")
                .isTrue();
        assertThat(normalised.data().path("rx_nr_pkts").longValue()).isEqualTo(176L);
        assertThat(normalised.data().path("rx_crc_bad").longValue()).isEqualTo(63L);
    }

    /**
     * The detail the prose loses. {@code fsk_received} and {@code timespan} arrive BARE
     * on the production dialect-C record while fifteen counters are prefixed, so a
     * normaliser that strips a prefix from everything, or that requires one everywhere,
     * fails on the only record this dialect exists for.
     */
    @Test
    void stripsThePrefixFromDialectCAndLeavesItsBareMembersAlone() {
        Normalised normalised = LorastatsDialects.normalise(payload("valid/lorastats-dialect-prefixed.json"));

        assertThat(normalised.dialect()).isEqualTo(Dialect.PREFIXED_INTEGER);
        assertThat(normalised.data().path("rx_nr_pkts").longValue()).isEqualTo(220L);
        assertThat(normalised.data().path("rx_short_pkt").longValue()).isEqualTo(172L);
        assertThat(normalised.data().path("fsk_received").longValue()).isEqualTo(0L);
        assertThat(normalised.data().path("timespan").longValue()).isEqualTo(1800L);
        assertThat(normalised.data().has("lora_rx_nr_pkts"))
                .as("no prefixed spelling survives normalisation")
                .isFalse();
    }

    /**
     * DL-105 rule 2, and the assertion the API depends on without knowing it. Three
     * dialects carrying the same counter must produce the same canonical member, or the
     * one stored shape is a claim rather than a property.
     */
    @Test
    void allThreeDialectsProduceOneCanonicalShape() {
        ObjectNode bare = MAPPER.createObjectNode();
        ObjectNode strings = MAPPER.createObjectNode();
        ObjectNode prefixed = MAPPER.createObjectNode();
        long value = 7L;
        for (String field : LorastatsDialects.REQUIRED_FIELDS) {
            bare.put(field, value);
            strings.put(field, Long.toString(value));
            prefixed.put("lora_" + field, value);
            value++;
        }

        Normalised fromBare = LorastatsDialects.normalise(bare);
        Normalised fromStrings = LorastatsDialects.normalise(strings);
        Normalised fromPrefixed = LorastatsDialects.normalise(prefixed);

        assertThat(List.of(fromBare.dialect(), fromStrings.dialect(), fromPrefixed.dialect()))
                .containsExactly(Dialect.BARE_INTEGER, Dialect.BARE_STRING, Dialect.PREFIXED_INTEGER);
        assertThat(fromStrings.data()).isEqualTo(fromBare.data());
        assertThat(fromPrefixed.data()).isEqualTo(fromBare.data());
    }

    /**
     * Rule 4. "All the known dialects" is exactly three, and this is the test that says
     * so: a fourth spelling surfaces on the DLQ rather than being absorbed into a union
     * that only ever grows.
     */
    @Test
    void anUnknownFourthDialectIsRefused() {
        assertThatThrownBy(
                        () ->
                                LorastatsDialects.normalise(
                                        payload("invalid/lorastats-dialect-unknown.json")))
                .isInstanceOf(PermanentFailureException.class)
                .hasMessageContaining("no known payload dialect");
    }

    @Test
    void refusesAPayloadThatMixesCounterTypes() {
        ObjectNode mixed = MAPPER.createObjectNode();
        boolean asText = false;
        for (String field : LorastatsDialects.REQUIRED_FIELDS) {
            if (asText) {
                mixed.put(field, "5");
            } else {
                mixed.put(field, 5L);
            }
            asText = !asText;
        }

        assertThatThrownBy(() -> LorastatsDialects.normalise(mixed))
                .isInstanceOf(PermanentFailureException.class)
                .hasMessageContaining("mixes counter value types");
    }

    @Test
    void refusesAPayloadCarryingBothSpellingsOfTheSameCounter() {
        ObjectNode both = bareInteger();
        for (String field : LorastatsDialects.REQUIRED_FIELDS) {
            both.put("lora_" + field, 1L);
        }

        assertThatThrownBy(() -> LorastatsDialects.normalise(both))
                .isInstanceOf(PermanentFailureException.class)
                .hasMessageContaining("more than one known dialect");
    }

    /**
     * Rule 3, and the reason it is a rule. The schema caps a string counter at nineteen
     * digits and the signed BIGINT maximum is also nineteen digits, so the schema cannot
     * hold this line. A coercion that caught the overflow and stored 0 would produce a
     * row that is WRONG rather than missing, and nothing anywhere would report it.
     */
    @Test
    void refusesAStringCounterThatDoesNotFitBigint() {
        ObjectNode overflowing = bareString();
        overflowing.put("rx_nr_pkts", "9223372036854775808");

        assertThatThrownBy(() -> LorastatsDialects.normalise(overflowing))
                .isInstanceOf(PermanentFailureException.class)
                .hasMessageContaining("rx_nr_pkts")
                .hasMessageContaining("BIGINT");
    }

    @Test
    void acceptsTheBigintMaximumItself() {
        ObjectNode atLimit = bareString();
        atLimit.put("rx_nr_pkts", Long.toString(Long.MAX_VALUE));

        assertThat(LorastatsDialects.normalise(atLimit).data().path("rx_nr_pkts").longValue())
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void refusesAStringCounterThatIsNotANumber() {
        ObjectNode notANumber = bareString();
        notANumber.put("tx_success", "many");

        assertThatThrownBy(() -> LorastatsDialects.normalise(notANumber))
                .isInstanceOf(PermanentFailureException.class)
                .hasMessageContaining("tx_success");
    }

    /**
     * D14b. An unrecognised member keeps its spelling and its value, in either dialect,
     * so the caller can preserve it verbatim in {@code data_extra}. Renaming an unknown
     * field on the way to a column that does not exist would be inventing a mapping.
     */
    @Test
    void leavesUnrecognisedMembersExactlyAsTheyArrived() {
        ObjectNode prefixed = MAPPER.createObjectNode();
        for (String field : LorastatsDialects.REQUIRED_FIELDS) {
            prefixed.put("lora_" + field, 1L);
        }
        prefixed.put("lora_experimental_flag", "on");
        prefixed.put("rx_future_counter", 4242L);

        JsonNode canonical = LorastatsDialects.normalise(prefixed).data();

        assertThat(canonical.path("lora_experimental_flag").textValue()).isEqualTo("on");
        assertThat(canonical.path("rx_future_counter").longValue()).isEqualTo(4242L);
    }

    @Test
    void refusesAPayloadThatIsNotAnObject() {
        assertThatThrownBy(() -> LorastatsDialects.normalise(MAPPER.createArrayNode()))
                .isInstanceOf(PermanentFailureException.class);
        assertThatThrownBy(() -> LorastatsDialects.normalise(null))
                .isInstanceOf(PermanentFailureException.class);
    }

    private static ObjectNode bareInteger() {
        ObjectNode data = MAPPER.createObjectNode();
        LorastatsDialects.REQUIRED_FIELDS.forEach(field -> data.put(field, 1L));
        return data;
    }

    private static ObjectNode bareString() {
        ObjectNode data = MAPPER.createObjectNode();
        LorastatsDialects.REQUIRED_FIELDS.forEach(field -> data.put(field, "1"));
        return data;
    }

    private static JsonNode payload(String fixture) {
        try (InputStream input =
                LorastatsDialectsTest.class.getClassLoader().getResourceAsStream(fixture)) {
            assertThat(input).as("fixture %s", fixture).isNotNull();
            return MAPPER.readTree(input).path("data");
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
