package com.zenner.datahub.stats.api.web;

import com.zenner.datahub.stats.common.error.ErrorCode;
import com.zenner.datahub.stats.common.error.FieldError;
import com.zenner.datahub.stats.common.error.FieldError.SafeMessage;
import com.zenner.datahub.stats.common.model.StatType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Parses the read API's path and query values into typed values, collecting every
 * malformed one before answering (openapi.yaml {@code parameters}).
 *
 * <p>Values are taken as strings and parsed here, not bound by Spring, so that every
 * malformed value is the contract's {@code invalid_parameter} naming the field — never a
 * framework type-mismatch message quoting the value back.</p>
 *
 * <p><strong>Seconds, bounded.</strong> A timestamp is Unix epoch <em>seconds</em>
 * between 2000-01-01 and 2100-01-01. A 13-digit millisecond value is outside that and is
 * {@code invalid_parameter}, never reinterpreted: read as seconds it is a date some
 * 53,000 years out that no query returns.</p>
 */
public final class RequestParameters {

    public static final long MIN_EPOCH_SECONDS = 946_684_800L; // 2000-01-01T00:00:00Z
    public static final long MAX_EPOCH_SECONDS = 4_102_444_800L; // 2100-01-01T00:00:00Z

    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,10}");
    private static final Pattern LIMIT = Pattern.compile("[0-9]{1,4}");
    private static final Pattern CLSBOX = Pattern.compile("[0-9A-Fa-f]{12}");
    private static final int MAX_UID_LENGTH = 128;

    private final StatType type;
    private final List<FieldError> errors = new ArrayList<>();

    private RequestParameters(StatType type) {
        this.type = type;
    }

    /**
     * The stat type is resolved first and alone: an unknown one is
     * {@code unknown_stat_type}, and it never reaches a table name.
     */
    public static RequestParameters forType(String rawType) {
        try {
            return new RequestParameters(StatType.fromWireName(rawType));
        } catch (IllegalArgumentException unknown) {
            throw ApiProblemException.validation(
                    ErrorCode.UNKNOWN_STAT_TYPE,
                    null,
                    FieldError.of(
                            "type",
                            ErrorCode.UNKNOWN_STAT_TYPE.wireCode(),
                            SafeMessage.UNSUPPORTED_STAT_TYPE));
        }
    }

    public StatType type() {
        return type;
    }

    /** A required epoch-seconds value; {@code null} (with an error recorded) when unusable. */
    public Long epochSeconds(String field, String raw) {
        if (raw == null || raw.isEmpty()) {
            reject(field, SafeMessage.MANDATORY_FIELD_ABSENT);
            return null;
        }
        if (!DIGITS.matcher(raw).matches()) {
            reject(field, SafeMessage.INVALID_PARAMETER);
            return null;
        }
        long value = Long.parseLong(raw);
        if (value < MIN_EPOCH_SECONDS || value > MAX_EPOCH_SECONDS) {
            reject(field, SafeMessage.INVALID_PARAMETER);
            return null;
        }
        return value;
    }

    /** {@code limit}: absent means the default; otherwise 1 to {@code max}, or an error. */
    public int limit(String raw, int defaultLimit, int max) {
        if (raw == null) {
            return defaultLimit;
        }
        if (!LIMIT.matcher(raw).matches()) {
            reject("limit", SafeMessage.INVALID_PARAMETER);
            return defaultLimit;
        }
        int value = Integer.parseInt(raw);
        if (value < 1 || value > max) {
            reject("limit", SafeMessage.INVALID_PARAMETER);
            return defaultLimit;
        }
        return value;
    }

    /**
     * {@code clsbox}: 12 hex characters in either case, returned in the stored canonical
     * uppercase so the same gateway asked for in either case is one walk.
     */
    public String clsbox(String raw) {
        if (raw == null || !CLSBOX.matcher(raw).matches()) {
            reject("clsbox", SafeMessage.INVALID_PARAMETER);
            return null;
        }
        return raw.toUpperCase(Locale.ROOT);
    }

    public String uid(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_UID_LENGTH) {
            reject("uid", SafeMessage.INVALID_PARAMETER);
            return null;
        }
        return raw;
    }

    /** Answers {@code invalid_parameter} with every field recorded so far, if any. */
    public void requireValid() {
        if (!errors.isEmpty()) {
            throw ApiProblemException.validation(ErrorCode.INVALID_PARAMETER, type, errors);
        }
    }

    private void reject(String field, SafeMessage message) {
        errors.add(FieldError.of(field, ErrorCode.INVALID_PARAMETER.wireCode(), message));
    }
}
