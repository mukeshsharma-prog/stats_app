package com.zenner.datahub.stats.writer.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.common.model.StatsEnvelope;
import com.zenner.datahub.stats.common.validation.StatsSchemaValidator;
import com.zenner.datahub.stats.common.validation.ValidationResult;
import com.zenner.datahub.stats.writer.retry.PermanentFailureException;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

/**
 * The Writer's half of the two-gate split: the per-type payload schema (T-057;
 * STA-03A, STA-12A, ADR-007 §5).
 *
 * <h2>Why a failure here is a dead-letter and not a 400</h2>
 *
 * <p>By the time a record reaches this class it has been accepted, publisher-confirmed
 * and published, and Heimdal retries only on a non-2xx — so the caller is long gone and
 * a rejection cannot reach it. Moving this check to Input would convert a redrivable
 * mismatch into unrecoverable data loss, which is window 12 of the eleven in
 * {@code claude/FLOW.md} §8. Input validates the <em>envelope</em>
 * ({@link StatsSchemaValidator#validateEnvelope}); this validates the envelope
 * <em>and</em> the payload ({@link StatsSchemaValidator#validate}). Two gates, two
 * consequences.</p>
 *
 * <h2>Why it is type-agnostic</h2>
 *
 * <p>Nothing here names a stat type. Rule 6 requires that adding a type is an additive
 * change, and a gate written inside one type's processor becomes five copies of itself
 * during the Phase 3 fan-out — at which point the fifth copy is the one that quietly
 * omits the check.</p>
 *
 * <h2>The body is parsed exactly once</h2>
 *
 * <p>The same tree is validated and then bound. Parsing twice would cost a second parse
 * on every one of 115.2m records a day, and — worse — would let the tree that was
 * validated and the object that was written drift apart, so the row stored would not be
 * the row that passed.</p>
 *
 * <h2>What the failure carries</h2>
 *
 * <p>{@link PermanentFailureException} is thrown with a <strong>constant</strong>
 * message. DL-124 makes it impossible for a throwable to reach a {@code stats-writer}
 * log line, and {@code SafeFailure}'s own javadoc names
 * {@code new PermanentFailureException("uid=… failed the schema")} as the temptation
 * this class was expected to fall into. The diagnosis is published instead: a
 * {@code WARN} carrying the {@link com.zenner.datahub.stats.common.error.FieldError}
 * list, which is value-free by construction — JSON Pointers, machine codes and reviewed
 * {@code SafeMessage} constants, never the rejected value — plus the record itself,
 * intact, on the type's dead-letter queue.</p>
 */
@Component
public class PayloadGate {

    private static final Logger LOGGER = LoggerFactory.getLogger(PayloadGate.class);

    /**
     * The one value that means the upstream conversion succeeded (DL-108). The check is
     * against this rather than against a list of known failure strings, deliberately: we
     * do not have the vocabulary of failures, and a check that recognises only the values
     * we happen to know about goes <em>quiet</em> on the ones we do not.
     */
    private static final List<String> CONVERSION_OK = List.of("conversion ok");

    private final ObjectMapper objectMapper;
    private final StatsSchemaValidator validator;
    private final WriterGateMetrics metrics;

    public PayloadGate(
            ObjectMapper objectMapper, StatsSchemaValidator validator, WriterGateMetrics metrics) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Parses, validates and binds one delivery.
     *
     * @throws PermanentFailureException if the body is not parseable, fails the schema
     *     for {@code expectedType}, or carries a different {@code type} than the queue it
     *     arrived on. All three recur identically on every future attempt, so the
     *     delivery skips the three retry tiers and goes straight to the DLQ — saving
     *     35½ minutes of queue time per record and three times the broker traffic.
     */
    public ValidatedRecord admit(StatType expectedType, Message message) {
        Objects.requireNonNull(expectedType, "expectedType");
        Objects.requireNonNull(message, "message");

        JsonNode record = parse(message);
        ValidationResult result = validator.validate(record);
        if (!result.valid()) {
            metrics.payloadRejected(expectedType, WriterGateMetrics.REASON_PAYLOAD_SCHEMA);
            // Not silent, and not a stack trace either (guard P2). The field errors are
            // safe to print by construction: pointers, codes and reviewed constants.
            // uid is the correlation ID by decision (D1) and is bounded by Input's
            // 256 KB request limit (DL-121), which is what makes printing it safe here.
            LOGGER.warn(
                    "Payload REJECTED by the {} schema; the record is dead-lettered without "
                            + "a retry: uid={}, fields={}",
                    expectedType.wireName(),
                    record.path("uid").asText(""),
                    result.errors());
            throw new PermanentFailureException("Payload failed its per-type schema");
        }

        StatsEnvelope envelope = bind(record);
        if (envelope.type() != expectedType) {
            // A record routed to the wrong queue never becomes right by waiting. It is
            // caught here rather than at the INSERT so it costs one delivery instead of
            // four, and so the failure names routing rather than a column mismatch.
            metrics.payloadRejected(expectedType, WriterGateMetrics.REASON_TYPE_MISMATCH);
            LOGGER.warn(
                    "Delivery on the {} queue declares a different type; dead-lettering: uid={}",
                    expectedType.wireName(),
                    envelope.uid());
            throw new PermanentFailureException("Record type does not match its queue");
        }

        meterUpstreamStatus(expectedType, envelope);
        return ValidatedRecord.of(envelope, result.schemaVersion());
    }

    /**
     * T-064 / DL-108. Q15d confirmed {@code status} can report a <em>failed</em>
     * conversion. The record is stored regardless — it is valid and was correctly
     * delivered, and a redrive would produce the identical verdict forever, in a DLQ
     * that ADR-002 §12 forbids ever expiring. {@code status} stays a passthrough member:
     * this reads it to count it and nothing branches on it.
     *
     * <p>An <strong>absent</strong> {@code status} is not counted. The member is optional
     * and every fixture written before 29 Aug 2026 omits it, so counting absence would
     * light the counter on ordinary traffic and drown the signal it exists to carry.
     * "Upstream did not say" and "upstream said it failed" are different facts; only the
     * second one is what DL-108 asked for.</p>
     */
    private void meterUpstreamStatus(StatType statType, StatsEnvelope envelope) {
        List<String> status = envelope.status();
        if (status == null || CONVERSION_OK.equals(status)) {
            return;
        }
        metrics.upstreamStatusNotOk(statType);
        // status is an ENVELOPE member, which DL-108 names explicitly; the DL-102 level
        // gate that keeps payloads at DEBUG does not extend to it.
        LOGGER.warn(
                "Upstream reported a non-OK conversion; the record is stored anyway: "
                        + "uid={}, type={}, clsbox={}, status={}",
                envelope.uid(),
                statType.wireName(),
                envelope.clsbox(),
                status);
    }

    private JsonNode parse(Message message) {
        try {
            return objectMapper.readTree(message.getBody());
        } catch (IOException notJson) {
            // Input validated the envelope, so this is a body that is not the JSON we
            // thought — and the same bytes will not parse on redelivery either.
            throw new PermanentFailureException("Delivery body is not parseable JSON", notJson);
        }
    }

    private StatsEnvelope bind(JsonNode record) {
        try {
            return objectMapper.treeToValue(record, StatsEnvelope.class);
        } catch (RuntimeException | JsonProcessingException cannotBind) {
            // The schema already passed, so a binding failure means the model and the
            // catalogue disagree — a defect on our side, not a bad record. It still
            // dead-letters rather than looping: nothing about the bytes will change.
            throw new PermanentFailureException(
                    "Record passed its schema but could not be bound", cannotBind);
        }
    }
}
