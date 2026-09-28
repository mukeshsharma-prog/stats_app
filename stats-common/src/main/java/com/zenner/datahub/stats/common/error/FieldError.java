package com.zenner.datahub.stats.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Safe per-field validation detail for RFC 9457 responses.
 *
 * <p>STA-03A forbids echoing an offending value. This type therefore has no
 * public constructor and accepts message text only through {@link SafeMessage},
 * whose values are static, reviewed strings. Callers can supply the field path
 * and machine-readable reason code, but cannot pass a runtime value as the
 * human-readable message.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class FieldError {

    private final String field;
    private final String code;
    private final String message;

    private FieldError(String field, String code, SafeMessage message) {
        this.field = requireText(field, "field");
        this.code = requireText(code, "code");
        this.message = message == null ? null : message.text;
    }

    public static FieldError of(String field, String code) {
        return new FieldError(field, code, null);
    }

    public static FieldError of(String field, String code, SafeMessage message) {
        return new FieldError(field, code, Objects.requireNonNull(message, "message"));
    }

    @JsonProperty("field")
    public String field() {
        return field;
    }

    @JsonProperty("code")
    public String code() {
        return code;
    }

    @JsonProperty("message")
    public String message() {
        return message;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FieldError that)) {
            return false;
        }
        return field.equals(that.field)
                && code.equals(that.code)
                && Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, code, message);
    }

    @Override
    public String toString() {
        return "FieldError[field=" + field + ", code=" + code + ", message=" + message + "]";
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /**
     * Reviewed messages that are safe to expose without including payload values.
     * T-004 maps schema keywords to these constants and discards validator text.
     */
    public enum SafeMessage {
        MANDATORY_FIELD_ABSENT("Mandatory field is absent."),
        INVALID_FIELD_TYPE("Field has an invalid type."),
        INVALID_PARAMETER("Field has an invalid value."),
        UNSUPPORTED_STAT_TYPE("Not a supported stat type."),
        // Read API (openapi.yaml FieldError catalogue, T-044). The wording of the first
        // two is the contract's own example text.
        TIME_RANGE_INVERTED("Must be earlier than 'to'."),
        QUERY_WINDOW_EXCEEDED(
                "Narrow the range, or read one gateway via /stats/{type}/{clsbox}/{from}."),
        INVALID_CURSOR("Cursor is malformed, expired, or was issued for a different query.");

        private final String text;

        SafeMessage(String text) {
            this.text = text;
        }
    }
}
