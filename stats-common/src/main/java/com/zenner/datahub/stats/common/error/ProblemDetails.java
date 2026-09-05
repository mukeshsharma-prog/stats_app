package com.zenner.datahub.stats.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.zenner.datahub.stats.common.model.StatType;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * Shared RFC 9457 response shape used by the Input and Stats API modules.
 *
 * <p>STA-03A and STA-35B require safe errors that do not echo payload values or
 * disclose another tenant's data. Validation failures should be created through
 * {@link #validationFailure(ErrorCode, StatType, List, String, String)}, which
 * supplies the catalogue-owned URI, title, and HTTP status.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemDetails(
        URI type,
        String title,
        int status,
        String detail,
        String instance,
        StatType statType,
        String traceId,
        List<FieldError> errors) {

    public static final String CONTENT_TYPE = "application/problem+json";

    public ProblemDetails {
        Objects.requireNonNull(type, "type");
        title = requireText(title, "title");
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("status must be between 400 and 599");
        }
        errors = errors == null ? null : List.copyOf(errors);
    }

    /**
     * A problem body for an error that has no per-field detail — 413, 429, 503 and
     * anything else whose cause is the request as a whole rather than one member.
     *
     * <p>It exists so those responses cannot drift from the catalogue: the type URI,
     * title and status all come from {@code errorCode}, and a caller therefore cannot
     * be handed a 429 carrying a 503 type URI because someone assembled the record by
     * hand at the call site.</p>
     */
    public static ProblemDetails of(ErrorCode errorCode, String detail, String instance) {
        return of(errorCode, detail, instance, null);
    }

    /** As {@link #of(ErrorCode, String, String)}, with the stat type when it is known. */
    public static ProblemDetails of(
            ErrorCode errorCode, String detail, String instance, StatType statType) {
        Objects.requireNonNull(errorCode, "errorCode");
        return new ProblemDetails(
                errorCode.typeUri(),
                errorCode.title(),
                errorCode.status(),
                detail,
                instance,
                statType,
                null,
                null);
    }

    public static ProblemDetails validationFailure(
            ErrorCode errorCode,
            List<FieldError> errors,
            String instance,
            String traceId) {
        return validationFailure(errorCode, null, errors, instance, traceId);
    }

    public static ProblemDetails validationFailure(
            ErrorCode errorCode,
            StatType statType,
            List<FieldError> errors,
            String instance,
            String traceId) {
        Objects.requireNonNull(errorCode, "errorCode");
        if (errorCode.status() != 400) {
            throw new IllegalArgumentException("validation failures must use an HTTP 400 error code");
        }

        List<FieldError> safeErrors = List.copyOf(Objects.requireNonNull(errors, "errors"));
        if (safeErrors.isEmpty()) {
            throw new IllegalArgumentException("validation failures require at least one field error");
        }

        String detail = safeErrors.size() == 1
                ? "1 field failed validation."
                : safeErrors.size() + " fields failed validation.";

        return new ProblemDetails(
                errorCode.typeUri(),
                errorCode.title(),
                errorCode.status(),
                detail,
                instance,
                statType,
                traceId,
                safeErrors);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
