package com.zenner.datahub.stats.writer.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.common.validation.StatsSchemaValidator;
import com.zenner.datahub.stats.writer.retry.PermanentFailureException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;

/**
 * The Writer's payload gate, against the real validator and the normative
 * fixtures.
 *
 * <p>The gate is exercised with the shared {@link StatsSchemaValidator} rather than a
 * mock on purpose: the two-gate split is only real if both gates are the same oracle, and
 * a mocked validator here would let this class pass while the two contracts drifted.</p>
 */
class PayloadGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final PayloadGate gate =
            new PayloadGate(MAPPER, new StatsSchemaValidator(), new WriterGateMetrics(registry));

    /** All three production dialects are ACCEPTED, and normalisation is later. */
    @ParameterizedTest(name = "{0} is admitted")
    @ValueSource(
            strings = {
                "valid/lorastats-production-canonical.json",
                "valid/lorastats-dialect-string-values.json",
                "valid/lorastats-dialect-prefixed.json"
            })
    void admitsEveryKnownDialect(String fixture) {
        ValidatedRecord admitted = gate.admit(StatType.LORASTATS, message(fixture));

        assertThat(admitted.envelope().type()).isEqualTo(StatType.LORASTATS);
        assertThat(admitted.schemaVersion())
                .as("the schema label that ADMITTED the record, not a constant")
                .isEqualTo((short) 1);
        assertThat(totalRejections()).isZero();
    }

    /**
     * Rule 4. The enumeration is closed at three, and a fourth spelling dead-letters
     * without a retry — nothing about it becomes true by waiting 35½ minutes.
     */
    @Test
    void refusesAnUnknownDialectAsAPermanentFailure() {
        assertThatThrownBy(
                        () ->
                                gate.admit(
                                        StatType.LORASTATS,
                                        message("invalid/lorastats-dialect-unknown.json")))
                .isInstanceOf(PermanentFailureException.class);

        assertThat(rejections(WriterGateMetrics.REASON_PAYLOAD_SCHEMA)).isEqualTo(1.0d);
        assertThat(rejections(WriterGateMetrics.REASON_TYPE_MISMATCH))
                .as("an unknown dialect is a schema refusal, not a routing fault")
                .isZero();
    }

    @ParameterizedTest(name = "{0} is dead-lettered")
    @ValueSource(
            strings = {
                "invalid/lorastats-missing-required.json",
                "invalid/lorastats-wrong-type.json"
            })
    void refusesAPayloadThatFailsItsSchema(String fixture) {
        assertThatThrownBy(() -> gate.admit(StatType.LORASTATS, message(fixture)))
                .isInstanceOf(PermanentFailureException.class);

        assertThat(rejections(WriterGateMetrics.REASON_PAYLOAD_SCHEMA)).isEqualTo(1.0d);
    }

    /**
     * The exact temptation {@code SafeFailure}'s javadoc names in advance:
     * {@code new PermanentFailureException("uid=… failed the schema")}. The message is a
     * constant. Nothing from the record is in it, so no future caller can leak a payload
     * value by printing it — a guard the scanner cannot express, because it cannot prove
     * what a string was built from.
     */
    @Test
    void theFailureMessageCarriesNothingFromTheRecord() {
        assertThatThrownBy(
                        () ->
                                gate.admit(
                                        StatType.LORASTATS,
                                        message("invalid/lorastats-missing-required.json")))
                .hasMessage("Payload failed its per-type schema");
    }

    @Test
    void refusesABodyThatIsNotJson() {
        assertThatThrownBy(
                        () ->
                                gate.admit(
                                        StatType.LORASTATS,
                                        new Message("not json at all".getBytes())))
                .isInstanceOf(PermanentFailureException.class)
                .hasMessage("Delivery body is not parseable JSON");
    }

    /** A record on the wrong queue never becomes right by waiting either. */
    @Test
    void refusesARecordWhoseTypeDoesNotMatchItsQueue() {
        assertThatThrownBy(
                        () ->
                                gate.admit(
                                        StatType.LORASTATS,
                                        message("valid/netstats-production-healthy.json")))
                .isInstanceOf(PermanentFailureException.class)
                .hasMessage("Record type does not match its queue");

        // The reason tag is the whole point. A record on the wrong queue is a binding or
        // routing fault on OUR side; a payload that fails its schema is a conversation
        // with Heimdal about a contract. Both dead-letter as PermanentFailureException,
        // so records_dead_lettered{reason} sees one class name for both and this counter
        // is the only place the two stay distinguishable.
        assertThat(rejections(WriterGateMetrics.REASON_TYPE_MISMATCH)).isEqualTo(1.0d);
        assertThat(rejections(WriterGateMetrics.REASON_PAYLOAD_SCHEMA))
                .as("a mis-routed record is not a schema refusal and must not be counted as one")
                .isZero();
    }

    // ---- Upstream status -----------------------------------------------------

    /**
     * {@code status} can report a FAILED conversion. The record is STORED — it is
     * valid and was correctly delivered, and a redrive would produce the identical
     * verdict forever in a DLQ that never expires — and the counter is the only signal on
     * our side that upstream already knew.
     */
    @Test
    void metersANonOkUpstreamStatusAndStillAdmitsTheRecord() {
        assertThatCode(
                        () ->
                                gate.admit(
                                        StatType.LORASTATS,
                                        message("valid/lorastats-status-not-ok.json")))
                .doesNotThrowAnyException();

        assertThat(upstreamStatusNotOk()).isEqualTo(1.0d);
        assertThat(totalRejections()).as("a non-OK status is never a dead-letter").isZero();
    }

    @Test
    void doesNotMeterTheOneKnownGoodStatus() {
        gate.admit(StatType.LORASTATS, message("valid/lorastats-production-canonical.json"));

        assertThat(upstreamStatusNotOk()).isZero();
    }

    /**
     * "Upstream did not say" and "upstream said it failed" are different facts, and only
     * the second is what is counted. {@code status} is optional and every fixture
     * written before 29 Aug 2026 omits it, so counting absence would light the counter on
     * ordinary traffic and drown the signal it exists to carry.
     */
    @Test
    void doesNotMeterAnAbsentStatus() {
        gate.admit(StatType.LORASTATS, message("valid/lorastats-valid.json"));

        assertThat(upstreamStatusNotOk()).isZero();
    }

    /**
     * The comparison is value-agnostic on purpose. A check that recognised only the
     * failure strings we happen to know about would go QUIET on the ones we do not, which
     * is the failure mode the whole "nothing is silently dropped" rule exists to prevent.
     */
    @Test
    void metersAStatusValueNobodyHasSeenBefore() {
        gate.admit(
                StatType.LORASTATS,
                message(
                        "valid/lorastats-production-canonical.json",
                        "[\"transcoder unavailable\", \"partial\"]"));

        assertThat(upstreamStatusNotOk()).isEqualTo(1.0d);
    }

    // ---- helpers ------------------------------------------------------------

    private double rejections(String reason) {
        Counter counter =
                registry.find(WriterGateMetrics.PAYLOAD_REJECTED)
                        .tag("type", StatType.LORASTATS.wireName())
                        .tag("reason", reason)
                        .counter();
        return counter == null ? 0.0d : counter.count();
    }

    /** Every reason together — for asserting that nothing was refused at all. */
    private double totalRejections() {
        return registry.find(WriterGateMetrics.PAYLOAD_REJECTED)
                .tag("type", StatType.LORASTATS.wireName())
                .counters()
                .stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    private double upstreamStatusNotOk() {
        Counter counter =
                registry.find(WriterGateMetrics.UPSTREAM_STATUS_NOT_OK)
                        .tag("type", StatType.LORASTATS.wireName())
                        .counter();
        return counter == null ? 0.0d : counter.count();
    }

    private static Message message(String fixture) {
        return new Message(read(fixture));
    }

    /** The same fixture with its {@code status} replaced, for the value-agnostic case. */
    private static Message message(String fixture, String statusJson) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode record =
                    (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(read(fixture));
            record.set("status", MAPPER.readTree(statusJson));
            return new Message(MAPPER.writeValueAsBytes(record));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static byte[] read(String fixture) {
        try (InputStream input =
                PayloadGateTest.class.getClassLoader().getResourceAsStream(fixture)) {
            assertThat(input).as("fixture %s", fixture).isNotNull();
            return input.readAllBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
