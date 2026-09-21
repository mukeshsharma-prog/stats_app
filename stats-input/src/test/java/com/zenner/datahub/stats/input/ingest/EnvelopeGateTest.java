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
import java.time.Duration;
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
 * the module has started destroying records that Heimdal will never resend
 * (ADR-007 §5, DL-088).</p>
 */
class EnvelopeGateTest {

    private static final String INSTANCE = "/internal/stats";

    private final EnvelopeGate gate =
            new EnvelopeGate(
                    new ObjectMapper(),
                    new StatsSchemaValidator(),
                    new InputValidationProperties(
                            InputValidationProperties.ENVELOPE_SCOPE, Duration.ofMinutes(5)));

    @Test
    @DisplayName("the canonical production record is accepted")
    void acceptsTheProductionRecord() {
        GateResult result = gate.evaluate(fixture("valid/lorastats-production-canonical.json"), INSTANCE);

        assertThat(result).isInstanceOf(GateResult.Accepted.class);
        GateResult.Accepted accepted = (GateResult.Accepted) result;
        assertThat(accepted.statType()).isEqualTo(StatType.LORASTATS);
        // ts.converter is on 100% of production records: accepted, never read, metered.
        assertThat(accepted.converterTimestampPresent()).isTrue();
        // T-050: the validated ts.server rides out with the result, in SECONDS, so the
        // controller can meter the reporting-cycle phase without re-parsing the body.
        assertThat(accepted.tsServer()).isEqualTo(java.time.Instant.ofEpochSecond(1_787_833_739L));
        assertThat(accepted.tsDeviceCorrected()).isFalse();
    }

    /**
     * DL-146: Heimdal flags a record whose {@code ts.device} it rewrote. The flag is read
     * to be counted (T-050) and for nothing else — the record is still accepted, and the
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
    @DisplayName("a PAYLOAD failure is never a 400 — ADR-007 §5")
    void acceptsPayloadFailures(String fixture) {
        // Every one of these fails its per-type payload schema and passes the
        // envelope. Heimdal retries only on a non-2xx, so a 400 here is unrecoverable
        // data loss, while a dead-letter at the Writer is redrivable. The dialect case
        // is the FOURTH, unexplained spelling: DL-105 accepted the three known dialects
        // on 4 Sep 2026 and T-063 built them, so the two production records that used to
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
                .as("retention keys on ts.device (STA-23B); late arrival is the Writer's call")
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
