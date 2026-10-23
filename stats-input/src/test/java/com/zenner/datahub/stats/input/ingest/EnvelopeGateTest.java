package com.zenner.datahub.stats.input.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.common.error.ErrorCode;
import com.zenner.datahub.stats.common.error.FieldError;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.common.validation.StatsSchemaValidator;
import com.zenner.datahub.stats.input.config.InputValidationProperties;
import com.zenner.datahub.stats.input.ingest.GateResult.RejectionReason;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The envelope gate, against the normative fixtures.
 *
 * <p>The load-bearing assertions here are the ones that prove Input does
 * <strong>not</strong> run the payload gate. If those ever flip to expecting a 400,
 * the module has started destroying records that Heimdal will never resend.
 * </p>
 */
class EnvelopeGateTest {

    private static final String INSTANCE = "/internal/stats";

    /** After every fixture's ts.server, so only the deliberately-future one is ahead. */
    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");

    private static final Duration SERVER_CLOCK_TOLERANCE = Duration.ofHours(1);

    private final EnvelopeGate gate =
            new EnvelopeGate(
                    new ObjectMapper(),
                    new StatsSchemaValidator(),
                    new InputValidationProperties(
                            InputValidationProperties.ENVELOPE_SCOPE,
                            Duration.ofMinutes(5),
                            SERVER_CLOCK_TOLERANCE),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("the canonical production record is accepted")
    void acceptsTheProductionRecord() {
        GateResult result = gate.evaluate(fixture("valid/lorastats-production-canonical.json"), INSTANCE);

        assertThat(result).isInstanceOf(GateResult.Accepted.class);
        GateResult.Accepted accepted = (GateResult.Accepted) result;
        assertThat(accepted.statType()).isEqualTo(StatType.LORASTATS);
        // ts.converter is on 100% of production records: accepted, never read, metered.
        assertThat(accepted.converterTimestampPresent()).isTrue();
        // The validated ts.server rides out with the result, in SECONDS, so the
        // controller can meter the reporting-cycle phase without re-parsing the body.
        assertThat(accepted.tsServer()).isEqualTo(java.time.Instant.ofEpochSecond(1_787_833_739L));
        assertThat(accepted.tsDeviceCorrected()).isFalse();
    }

    /**
     * Heimdal flags a record whose {@code ts.device} it rewrote. The flag is read
     * to be counted and for nothing else — the record is still accepted, and the
     * skew check sees the corrected value.
     */
    @Test
    @DisplayName("a record Heimdal marked tsDeviceCorrected is accepted and the flag is reported")
    void reportsHeimdalsTsDeviceCorrection() {
        GateResult result =
                gate.evaluate(fixture("valid/sysstats-production-ts-device-corrected.json"), INSTANCE);

        assertThat(result).isInstanceOf(GateResult.Accepted.class);
        GateResult.Accepted accepted = (GateResult.Accepted) result;
        assertThat(accepted.statType()).isEqualTo(StatType.SYSSTATS);
        assertThat(accepted.tsDeviceCorrected()).isTrue();
        assertThat(accepted.tsServer()).isEqualTo(java.time.Instant.ofEpochSecond(1_786_617_001L));
    }

    @ParameterizedTest(name = "{0} is ACCEPTED by Input and left for the Writer to dead-letter")
    @ValueSource(
            strings = {
                "invalid/lorastats-dialect-unknown.json",
                "invalid/lorastats-missing-required.json",
                "invalid/lorastats-wrong-type.json"
            })
    @DisplayName("a PAYLOAD failure is never a 400")
    void acceptsPayloadFailures(String fixture) {
        // Every one of these fails its per-type payload schema and passes the
        // envelope. Heimdal retries only on a non-2xx, so a 400 here is unrecoverable
        // data loss, while a dead-letter at the Writer is redrivable. The dialect case
        // is the FOURTH, unexplained spelling: the three known dialects are accepted
        // since 8 Sep 2026, so the two production records that used to
        // sit here are now valid/ fixtures and Input's behaviour toward them is covered
        // by acceptsTheProductionRecord and its siblings.
        GateResult result = gate.evaluate(fixture(fixture), INSTANCE);

        assertThat(result)
                .as("Input must accept a payload disagreement and let the Writer dead-letter it")
                .isInstanceOf(GateResult.Accepted.class);
        assertThat(((GateResult.Accepted) result).statType()).isEqualTo(StatType.LORASTATS);
    }

    @Test
    @DisplayName("ts.device an hour ahead of ts.server is rejected with clock_skew_exceeded")
    void rejectsClockSkew() {
        GateResult result = gate.evaluate(fixture("edge/lorastats-clock-skew-future.json"), INSTANCE);

        assertThat(result).isInstanceOf(GateResult.Rejected.class);
        GateResult.Rejected rejected = (GateResult.Rejected) result;
        assertThat(rejected.reason()).isEqualTo(RejectionReason.CLOCK_SKEW_EXCEEDED);
        assertThat(rejected.statType()).isEqualTo(StatType.LORASTATS);
        assertThat(rejected.problem().status()).isEqualTo(400);
        assertThat(rejected.problem().errors())
                .singleElement()
                .satisfies(
                        error -> {
                            assertThat(error.field()).isEqualTo(ClockSkewPolicy.CLOCK_SKEW_POINTER);
                            assertThat(error.code()).isEqualTo(ClockSkewPolicy.CLOCK_SKEW_CODE);
                        });
    }

    @Test
    @DisplayName("a record whose ts.device is far in the PAST is accepted, not skew-rejected")
    void acceptsLateArrival() {
        GateResult result = gate.evaluate(fixture("edge/lorastats-late-arrival.json"), INSTANCE);

        assertThat(result)
                .as("retention keys on ts.device; late arrival is the Writer's call")
                .isInstanceOf(GateResult.Accepted.class);
    }

    @Test
    @DisplayName("an envelope failure is a 400 and names the field")
    void rejectsMissingEnvelopeField() {
        byte[] body = "{\"tenant\":\"a\",\"maintenant\":\"b\",\"type\":\"lorastats\"}"
                .getBytes(StandardCharsets.UTF_8);

        GateResult result = gate.evaluate(body, INSTANCE);

        assertThat(result).isInstanceOf(GateResult.Rejected.class);
        GateResult.Rejected rejected = (GateResult.Rejected) result;
        assertThat(rejected.reason()).isEqualTo(RejectionReason.INVALID_ENVELOPE);
        assertThat(rejected.problem().status()).isEqualTo(400);
        assertThat(rejected.problem().errors())
                .extracting(FieldError::field)
                .contains("/uid", "/clsbox", "/ts", "/data");
    }

    @Test
    @DisplayName("an unknown stat type is unknown_stat_type, not a generic parameter error")
    void rejectsUnknownStatType() {
        byte[] body =
                ("{\"uid\":\"CLSCON-1\",\"tenant\":\"a\",\"maintenant\":\"b\",\"clsbox\":\"04B64810652B\","
                                + "\"type\":\"weatherstats\",\"ts\":{\"server\":1787054120,\"device\":1787054100},"
                                + "\"data\":{}}")
                        .getBytes(StandardCharsets.UTF_8);

        GateResult result = gate.evaluate(body, INSTANCE);

        GateResult.Rejected rejected = (GateResult.Rejected) result;
        assertThat(rejected.statType()).isNull();
        assertThat(rejected.problem().type()).isEqualTo(ErrorCode.UNKNOWN_STAT_TYPE.typeUri());
        assertThat(rejected.problem().errors())
                .singleElement()
                .satisfies(error -> assertThat(error.field()).isEqualTo("/type"));
    }

    @Test
    @DisplayName("a body that is not JSON is rejected without reaching the validator")
    void rejectsMalformedJson() {
        GateResult result =
                gate.evaluate("{\"uid\": \"CLSCON-".getBytes(StandardCharsets.UTF_8), INSTANCE);

        GateResult.Rejected rejected = (GateResult.Rejected) result;
        assertThat(rejected.reason()).isEqualTo(RejectionReason.MALFORMED_JSON);
        assertThat(rejected.statType()).isNull();
        assertThat(rejected.problem().status()).isEqualTo(400);
    }

    @Test
    @DisplayName("a top-level JSON array is rejected — the envelope must be an object")
    void rejectsTopLevelArray() {
        GateResult result = gate.evaluate("[]".getBytes(StandardCharsets.UTF_8), INSTANCE);

        assertThat(result).isInstanceOf(GateResult.Rejected.class);
        assertThat(((GateResult.Rejected) result).problem().status()).isEqualTo(400);
    }

    @Test
    @DisplayName("no response body ever echoes a value from the record")
    void neverEchoesOffendingValues() {
        GateResult result = gate.evaluate(fixture("invalid/lorastats-wrong-type.json"), INSTANCE);

        // This fixture passes the envelope, so it is accepted. The leak probe that
        // matters for rejected records lives in ProblemDetailsTest; here we assert the
        // gate did not manufacture an error carrying payload text.
        assertThat(result).isInstanceOf(GateResult.Accepted.class);
    }

    // ---- Nothing is answered 200 that PostgreSQL cannot hold --------------------

    @ParameterizedTest(name = "{0} is malformed_json")
    @ValueSource(
            strings = {
                "malformed/invalid-utf8.json",
                "malformed/trailing-content.json",
                "malformed/duplicate-keys.json"
            })
    @DisplayName("the body is parsed as bytes, strictly")
    void rejectsWhatTheWriterCouldNotParseTheSameWay(String fixture) {
        // Each of these used to be a 200. Invalid UTF-8 was decoded to U+FFFD here and
        // refused by the Writer, which parses the same bytes; a duplicated member resolved
        // to whichever copy this parser kept; trailing content was silently ignored.
        GateResult result = gate.evaluate(fixture(fixture), INSTANCE);

        assertThat(result).isInstanceOf(GateResult.Rejected.class);
        GateResult.Rejected rejected = (GateResult.Rejected) result;
        assertThat(rejected.reason()).isEqualTo(RejectionReason.MALFORMED_JSON);
        assertThat(rejected.problem().status()).isEqualTo(400);
    }

    @Test
    @DisplayName("U+0000 in a payload value is unstorable_character, pointing at the member")
    void rejectsNulCharacter() {
        GateResult result =
                gate.evaluate(fixture("edge/envelope-unstorable-character.json"), INSTANCE);

        GateResult.Rejected rejected = (GateResult.Rejected) result;
        assertThat(rejected.reason()).isEqualTo(RejectionReason.UNSTORABLE_CHARACTER);
        assertThat(rejected.statType()).isEqualTo(StatType.SYSSTATS);
        assertThat(rejected.problem().errors())
                .singleElement()
                .satisfies(
                        error -> {
                            assertThat(error.field()).isEqualTo("/data/note");
                            assertThat(error.code()).isEqualTo(EnvelopeGate.UNSTORABLE_CHARACTER_CODE);
                        });
    }

    @Test
    @DisplayName("U+0000 in a member NAME is reported at the parent, never by the bad name")
    void rejectsNulInMemberName() {
        byte[] body = withData("\"bad\\u0000key\":1");

        GateResult.Rejected rejected = (GateResult.Rejected) gate.evaluate(body, INSTANCE);

        assertThat(rejected.reason()).isEqualTo(RejectionReason.UNSTORABLE_CHARACTER);
        assertThat(rejected.problem().errors())
                .singleElement()
                .satisfies(error -> assertThat(error.field()).isEqualTo("/data"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"\\ud800", "\\udc00", "x\\ud800y"})
    @DisplayName("an unpaired surrogate is unstorable_character")
    void rejectsUnpairedSurrogates(String escaped) {
        GateResult result = gate.evaluate(withData("\"note\":\"" + escaped + "\""), INSTANCE);

        assertThat(((GateResult.Rejected) result).reason())
                .isEqualTo(RejectionReason.UNSTORABLE_CHARACTER);
    }

    @Test
    @DisplayName("a valid surrogate PAIR (an emoji) is stored like any other text")
    void acceptsSurrogatePairs() {
        GateResult result =
                gate.evaluate(withData("\"note\":\"\\ud83d\\ude00\""), INSTANCE);

        assertThat(result).isInstanceOf(GateResult.Accepted.class);
    }

    @Test
    @DisplayName("ts.server decades ahead of real time is server_clock_ahead")
    void rejectsServerClockAhead() {
        GateResult result = gate.evaluate(fixture("edge/envelope-server-clock-ahead.json"), INSTANCE);

        GateResult.Rejected rejected = (GateResult.Rejected) result;
        assertThat(rejected.reason()).isEqualTo(RejectionReason.SERVER_CLOCK_AHEAD);
        assertThat(rejected.problem().errors())
                .singleElement()
                .satisfies(
                        error -> {
                            assertThat(error.field()).isEqualTo(EnvelopeGate.SERVER_CLOCK_AHEAD_POINTER);
                            assertThat(error.code()).isEqualTo(EnvelopeGate.SERVER_CLOCK_AHEAD_CODE);
                        });
    }

    @Test
    @DisplayName("ts.server exactly at the tolerance is accepted; one second past it is not")
    void serverClockBoundary() {
        long atTolerance = NOW.plus(SERVER_CLOCK_TOLERANCE).getEpochSecond();

        assertThat(gate.evaluate(withServerAndDevice(atTolerance), INSTANCE))
                .isInstanceOf(GateResult.Accepted.class);
        assertThat(gate.evaluate(withServerAndDevice(atTolerance + 1), INSTANCE))
                .isInstanceOf(GateResult.Rejected.class)
                .extracting(rejected -> ((GateResult.Rejected) rejected).reason())
                .isEqualTo(RejectionReason.SERVER_CLOCK_AHEAD);
    }

    @ParameterizedTest(name = "{0} is an envelope 400")
    @ValueSource(
            strings = {
                "invalid/envelope-uid-too-long.json",
                "invalid/envelope-tenant-blank.json",
                "invalid/envelope-version-too-large.json"
            })
    @DisplayName("the envelope bounds the identifiers and version the Writer can store")
    void rejectsUnstorableEnvelopeValues(String fixture) {
        GateResult result = gate.evaluate(fixture(fixture), INSTANCE);

        assertThat(result).isInstanceOf(GateResult.Rejected.class);
        assertThat(((GateResult.Rejected) result).reason()).isEqualTo(RejectionReason.INVALID_ENVELOPE);
    }

    private static byte[] withData(String extraMembers) {
        return ("{\"uid\":\"CLSCON-1\",\"tenant\":\"a\",\"maintenant\":\"b\","
                        + "\"clsbox\":\"04B64810652B\",\"type\":\"sysstats\","
                        + "\"ts\":{\"server\":1787054120,\"device\":1787054100},"
                        + "\"data\":{" + extraMembers + "}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] withServerAndDevice(long epochSeconds) {
        return ("{\"uid\":\"CLSCON-1\",\"tenant\":\"a\",\"maintenant\":\"b\","
                        + "\"clsbox\":\"04B64810652B\",\"type\":\"sysstats\","
                        + "\"ts\":{\"server\":" + epochSeconds + ",\"device\":" + epochSeconds + "},"
                        + "\"data\":{}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] fixture(String name) {
        try (InputStream in =
                EnvelopeGateTest.class.getClassLoader().getResourceAsStream(name)) {
            return Objects.requireNonNull(in, "fixture not on the test classpath: " + name)
                    .readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("could not read fixture " + name, e);
        }
    }
}
