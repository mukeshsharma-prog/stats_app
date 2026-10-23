package com.zenner.datahub.stats.input.ingest;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
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
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Input's gate: parse, validate the <strong>envelope only</strong>, then check clock
 * skew. Nothing here publishes, and nothing here touches the payload.
 *
 * <p><strong>Why the payload is not validated.</strong> Heimdal
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
 * payload. Failures are logged with the stat type and the reason only — an offending
 * value is never echoed, and that applies to our own logs as much as
 * to the response body.</p>
 *
 * <p><strong>Nothing is answered 200 that PostgreSQL cannot hold.</strong> Three
 * rules sit beside the schema because no schema keyword can express them, and each one
 * closes a path on which a record used to be accepted, published and then dead-lettered
 * forever, since every redrive re-derives the same failure:</p>
 *
 * <ul>
 *   <li>the body is parsed <em>as bytes</em>, strictly — invalid UTF-8, a duplicated
 *       member name and anything after the closing brace are {@code malformed_json}.
 *       Decoding to a {@code String} first replaced bad bytes with U+FFFD here while the
 *       Writer, parsing the same bytes, refused them; and a duplicated {@code tenant}
 *       resolved to whichever copy this parser happens to keep;</li>
 *   <li>U+0000 or an unpaired surrogate anywhere — member name or string value, envelope
 *       or payload — is {@code unstorable_character}: PostgreSQL's UTF-8 cannot represent
 *       either, in {@code text} (SQLSTATE 22021) or {@code jsonb} (22P05);</li>
 *   <li>{@code ts.server} further ahead of this instance's clock than
 *       {@code max-server-clock-ahead} is {@code server_clock_ahead}: no partition exists
 *       that far out (SQLSTATE 23514).</li>
 * </ul>
 */
@Component
public class EnvelopeGate {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeGate.class);

    private static final String MALFORMED_JSON_CODE = "malformed_json";

    /** Field-level code for U+0000 or an unpaired surrogate. */
    public static final String UNSTORABLE_CHARACTER_CODE = "unstorable_character";

    /** Field-level code for a {@code ts.server} ahead of real time. */
    public static final String SERVER_CLOCK_AHEAD_CODE = "server_clock_ahead";

    /** JSON Pointer of the field {@link #SERVER_CLOCK_AHEAD_CODE} names. */
    public static final String SERVER_CLOCK_AHEAD_POINTER = "/ts/server";
    private static final String TS = "ts";
    private static final String SERVER = "server";
    private static final String DEVICE = "device";
    private static final String CONVERTER = "converter";

    private final ObjectReader reader;
    private final StatsSchemaValidator validator;
    private final ClockSkewPolicy clockSkewPolicy;
    private final Duration maxServerClockAhead;
    private final Clock clock;

    public EnvelopeGate(
            ObjectMapper objectMapper,
            StatsSchemaValidator validator,
            InputValidationProperties properties,
            Clock clock) {
        // Strict for THIS parse only: the shared mapper also writes every response, and
        // the Writer stays lenient so nothing already queued starts dead-lettering.
        this.reader =
                Objects.requireNonNull(objectMapper, "objectMapper")
                        .reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.validator = Objects.requireNonNull(validator, "validator");
        Objects.requireNonNull(properties, "properties");
        this.clockSkewPolicy = new ClockSkewPolicy(properties.maxClockSkew());
        this.maxServerClockAhead = properties.maxServerClockAhead();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param body the exact bytes received. They are parsed for inspection only; the
     *     bytes themselves are what gets published, so that publication stays
     *     byte-identical to what arrived.
     * @param instance the request path, for the RFC 9457 {@code instance} member.
     */
    public GateResult evaluate(byte[] body, String instance) {
        Objects.requireNonNull(body, "body");

        JsonNode record;
        try {
            // The bytes, not a String decoded from them: the Writer parses the bytes, so
            // this gate must refuse exactly what the Writer would.
            record = reader.readTree(body);
        } catch (IOException malformed) {
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

        String unstorable = firstUnstorableCharacter(record);
        if (unstorable != null) {
            IngestLogContext.errorCode(ErrorCode.INVALID_PARAMETER);
            // The pointer is logged, never the value — the value is the problem.
            log.atWarn()
                    .addKeyValue("event", "validation_failed")
                    .addKeyValue("validationCode", UNSTORABLE_CHARACTER_CODE)
                    .log(
                            "Rejected a record carrying U+0000 or an unpaired surrogate, which "
                                    + "PostgreSQL cannot store; nothing published");
            return rejection(
                    ErrorCode.INVALID_PARAMETER,
                    statType,
                    List.of(
                            FieldError.of(
                                    unstorable,
                                    UNSTORABLE_CHARACTER_CODE,
                                    SafeMessage.INVALID_PARAMETER)),
                    instance,
                    RejectionReason.UNSTORABLE_CHARACTER);
        }

        // Safe from here: the envelope schema has already guaranteed that ts.server
        // and ts.device are present and are epoch-second integers.
        Instant server = epochSeconds(record, SERVER);
        Instant device = epochSeconds(record, DEVICE);

        if (server.isAfter(clock.instant().plus(maxServerClockAhead))) {
            IngestLogContext.errorCode(ErrorCode.INVALID_PARAMETER);
            log.atWarn()
                    .addKeyValue("event", "validation_failed")
                    .addKeyValue("validationCode", SERVER_CLOCK_AHEAD_CODE)
                    .addKeyValue("serverClockTolerance", maxServerClockAhead)
                    .log(
                            "Rejected a record whose ts.server is ahead of real time by more "
                                    + "than the tolerance; fix the sending clock");
            return rejection(
                    ErrorCode.INVALID_PARAMETER,
                    statType,
                    List.of(
                            FieldError.of(
                                    SERVER_CLOCK_AHEAD_POINTER,
                                    SERVER_CLOCK_AHEAD_CODE,
                                    SafeMessage.INVALID_PARAMETER)),
                    instance,
                    RejectionReason.SERVER_CLOCK_AHEAD);
        }

        if (clockSkewPolicy.exceeded(device, server)) {
            IngestLogContext.errorCode(ErrorCode.INVALID_PARAMETER);
            log.atWarn()
                    .addKeyValue("event", "validation_failed")
                    .addKeyValue("validationCode", ClockSkewPolicy.CLOCK_SKEW_CODE)
                    .addKeyValue("clockSkewTolerance", clockSkewPolicy.tolerance())
                    .log(
                            "Rejected a record because clock skew exceeds tolerance; fix the "
                                    + "gateway clock");
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

        return new GateResult.Accepted(
                statType, hasConverterTimestamp(record), server, isTsDeviceCorrected(record));
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

    /**
     * Heimdal's own flag that it rewrote {@code ts.device}. Read here to count
     * it and for nothing else: the value is preserved as a passthrough member and never
     * branched on. A missing or non-boolean member is simply "not corrected".
     */
    private static boolean isTsDeviceCorrected(JsonNode record) {
        JsonNode flag = record.path("meta").path("tsDeviceCorrected");
        return flag.isBoolean() && flag.booleanValue();
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

    /**
     * The JSON Pointer of the first member name or string value PostgreSQL cannot store,
     * or {@code null}. A bad member name is reported by its parent's pointer, so the
     * pointer never carries the offending text. Iterative: depth is bounded by the
     * parser's nesting limit, but a stack frame per level is still not needed.
     */
    static String firstUnstorableCharacter(JsonNode root) {
        Deque<Map.Entry<String, JsonNode>> pending = new ArrayDeque<>();
        pending.push(Map.entry("", root));
        while (!pending.isEmpty()) {
            Map.Entry<String, JsonNode> next = pending.pop();
            String pointer = next.getKey();
            JsonNode node = next.getValue();
            if (node.isTextual()) {
                if (!isStorable(node.textValue())) {
                    return pointer.isEmpty() ? "/" : pointer;
                }
            } else if (node.isObject()) {
                for (Map.Entry<String, JsonNode> member : node.properties()) {
                    if (!isStorable(member.getKey())) {
                        return pointer.isEmpty() ? "/" : pointer;
                    }
                    pending.push(Map.entry(pointer + "/" + escape(member.getKey()), member.getValue()));
                }
            } else if (node.isArray()) {
                for (int index = 0; index < node.size(); index++) {
                    pending.push(Map.entry(pointer + "/" + index, node.get(index)));
                }
            }
        }
        return null;
    }

    /** False for U+0000 and for a surrogate that is not half of a valid pair. */
    static boolean isStorable(String text) {
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\0') {
                return false;
            }
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }

    /** RFC 6901: {@code ~} is {@code ~0} and {@code /} is {@code ~1}. */
    private static String escape(String memberName) {
        return memberName.replace("~", "~0").replace("/", "~1");
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
