package com.zenner.datahub.stats.input.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.common.error.ErrorCode;
import com.zenner.datahub.stats.common.error.FieldError;
import com.zenner.datahub.stats.common.error.FieldError.SafeMessage;
import com.zenner.datahub.stats.common.error.ProblemDetails;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.common.validation.StatsSchemaValidator;
import com.zenner.datahub.stats.common.validation.ValidationResult;
import com.zenner.datahub.stats.input.config.InputValidationProperties;
import com.zenner.datahub.stats.input.ingest.GateResult.RejectionReason;
import com.zenner.datahub.stats.input.log.IngestLogContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Input's gate: parse, validate the <strong>envelope only</strong>, then check clock
 * skew. Nothing here publishes, and nothing here touches the payload.
 *
 * <p><strong>Why the payload is not validated (ADR-007 §5, DL-088).</strong> Heimdal
 * retries only on a non-2xx. A 400 issued because {@code data} disagreed with a
 * per-type schema is therefore <em>unrecoverable data loss</em>, while the same
 * disagreement caught at the Writer is a dead-letter that can be redriven once the
 * schema is fixed. Production sends {@code lorastats} in three mutually incompatible
 * dialects today, all carrying {@code version: 1}, so this is a live concern rather
 * than a theoretical one. Call
 * {@link StatsSchemaValidator#validateEnvelope(JsonNode)} here, never
 * {@link StatsSchemaValidator#validate(JsonNode)}.</p>
 *
 * <p>The gate returns a decision; it never throws for a bad record and never logs a
 * payload. Failures are logged with the stat type and the reason only — STA-03A
 * forbids echoing the offending value, and that applies to our own logs as much as
 * to the response body.</p>
 */
@Component
public class EnvelopeGate {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeGate.class);

    private static final String MALFORMED_JSON_CODE = "malformed_json";
    private static final String TS = "ts";
    private static final String SERVER = "server";
    private static final String DEVICE = "device";
    private static final String CONVERTER = "converter";

    private final ObjectMapper objectMapper;
    private final StatsSchemaValidator validator;
    private final ClockSkewPolicy clockSkewPolicy;

    public EnvelopeGate(
            ObjectMapper objectMapper,
            StatsSchemaValidator validator,
            InputValidationProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.clockSkewPolicy =
                new ClockSkewPolicy(
                        Objects.requireNonNull(properties, "properties").maxClockSkew());
    }

    /**
     * @param body the exact bytes received. They are parsed for inspection only; the
     *     bytes themselves are what gets published, so that publication stays
     *     byte-identical to what arrived (ADR-004, T-016).
     * @param instance the request path, for the RFC 9457 {@code instance} member.
     */
    public GateResult evaluate(byte[] body, String instance) {
        Objects.requireNonNull(body, "body");

        JsonNode record;
        try {
            record = objectMapper.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (JsonProcessingException malformed) {
            // The message may quote the offending input, so it is deliberately not
            // propagated into the response or the log line.
            IngestLogContext.errorCode(ErrorCode.INVALID_PARAMETER);
            log.atWarn()
                    .addKeyValue("event", "validation_failed")
                    .log(
                            "Rejected a request body that is not valid JSON: parse failed at the "
                                    + "Input gate; nothing published");
            return rejection(
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    List.of(FieldError.of("/", MALFORMED_JSON_CODE, SafeMessage.INVALID_PARAMETER)),
                    instance,
                    RejectionReason.MALFORMED_JSON);
        }

        if (record == null || record.isMissingNode()) {
            IngestLogContext.errorCode(ErrorCode.INVALID_PARAMETER);
            log.atWarn()
                    .addKeyValue("event", "validation_failed")
                    .log("Rejected an empty JSON value at the Input gate; nothing published");
            return rejection(
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    List.of(FieldError.of("/", MALFORMED_JSON_CODE, SafeMessage.INVALID_PARAMETER)),
                    instance,
                    RejectionReason.MALFORMED_JSON);
        }

        ValidationResult envelope = validator.validateEnvelope(record);
        StatType statType = readStatType(record);
        IngestLogContext.statType(statType);

        if (!envelope.valid()) {
            ErrorCode errorCode =
                    isUnknownStatType(envelope) ? ErrorCode.UNKNOWN_STAT_TYPE : ErrorCode.INVALID_PARAMETER;
            IngestLogContext.errorCode(errorCode);
            log.atWarn()
                    .addKeyValue("event", "validation_failed")
                    .addKeyValue("fieldErrorCount", envelope.errors().size())
                    .log("Rejected a record at the envelope gate; nothing published");
            return rejection(
                    errorCode, statType, envelope.errors(), instance, RejectionReason.INVALID_ENVELOPE);
        }

        IngestLogContext.bindValidatedRecord(record, statType);

        // Safe from here: the envelope schema has already guaranteed that ts.server
        // and ts.device are present and are epoch-second integers.
        Instant server = epochSeconds(record, SERVER);
        Instant device = epochSeconds(record, DEVICE);

        if (clockSkewPolicy.exceeded(device, server)) {
            IngestLogContext.errorCode(ErrorCode.INVALID_PARAMETER);
            log.atWarn()
                    .addKeyValue("event", "validation_failed")
                    .addKeyValue("validationCode", ClockSkewPolicy.CLOCK_SKEW_CODE)
                    .addKeyValue("clockSkewTolerance", clockSkewPolicy.tolerance())
                    .log(
                            "Rejected a record because clock skew exceeds tolerance; fix the "
                                    + "gateway clock (Q14a, ADR-003 D9)");
            return rejection(
                    ErrorCode.INVALID_PARAMETER,
                    statType,
                    List.of(
                            FieldError.of(
                                    ClockSkewPolicy.CLOCK_SKEW_POINTER,
                                    ClockSkewPolicy.CLOCK_SKEW_CODE,
                                    SafeMessage.INVALID_PARAMETER)),
                    instance,
                    RejectionReason.CLOCK_SKEW_EXCEEDED);
        }

        return new GateResult.Accepted(statType, hasConverterTimestamp(record));
    }

    private GateResult rejection(
            ErrorCode errorCode,
            StatType statType,
            List<FieldError> errors,
            String instance,
            RejectionReason reason) {
        ProblemDetails problem =
                ProblemDetails.validationFailure(
                        errorCode, statType, errors, instance, IngestLogContext.traceId());
        return new GateResult.Rejected(problem, statType, reason);
    }

    private static boolean isUnknownStatType(ValidationResult envelope) {
        return envelope.errors().stream()
                .anyMatch(error -> ErrorCode.UNKNOWN_STAT_TYPE.wireCode().equals(error.code()));
    }

    /**
     * Resolves the stat type for metric and response labelling. Returns null rather
     * than throwing when the literal is absent or unknown — the schema failure is
     * already the reported error, and a second exception would replace a precise 400
     * with a 500.
     */
    private static StatType readStatType(JsonNode record) {
        JsonNode typeNode = record.get("type");
        if (typeNode == null || !typeNode.isTextual()) {
            return null;
        }
        try {
            return StatType.fromWireName(typeNode.textValue());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static Instant epochSeconds(JsonNode record, String member) {
        return Instant.ofEpochSecond(record.get(TS).get(member).asLong());
    }

    private static boolean hasConverterTimestamp(JsonNode record) {
        JsonNode ts = record.get(TS);
        JsonNode converter = ts == null ? null : ts.get(CONVERTER);
        return converter != null && !converter.isNull();
    }
}
