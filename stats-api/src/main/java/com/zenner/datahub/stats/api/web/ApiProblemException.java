package com.zenner.datahub.stats.api.web;

import com.zenner.datahub.stats.common.error.ErrorCode;
import com.zenner.datahub.stats.common.error.FieldError;
import com.zenner.datahub.stats.common.error.ProblemDetails;
import com.zenner.datahub.stats.common.model.StatType;
import java.util.List;
import java.util.Objects;

/**
 * A request the API answers with a catalogued problem. Built only from an
 * {@link ErrorCode} and {@link FieldError}s, whose messages are reviewed constants — so
 * nothing the caller sent, and nothing the database said, can reach a response body.
 */
public final class ApiProblemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final transient StatType statType;
    private final transient List<FieldError> errors;
    private final String detail;

    private ApiProblemException(
            ErrorCode code, StatType statType, List<FieldError> errors, String detail) {
        super(code.wireCode(), null, false, false);
        this.code = code;
        this.statType = statType;
        this.errors = errors == null ? null : List.copyOf(errors);
        this.detail = detail;
    }

    /** A 400 with per-field detail. */
    public static ApiProblemException validation(
            ErrorCode code, StatType statType, List<FieldError> errors) {
        Objects.requireNonNull(errors, "errors");
        return new ApiProblemException(code, statType, errors, null);
    }

    /** A 400 for one field. */
    public static ApiProblemException validation(
            ErrorCode code, StatType statType, FieldError error) {
        return validation(code, statType, List.of(error));
    }

    /** A problem about the request as a whole, such as {@code record_not_found}. */
    public static ApiProblemException of(ErrorCode code, StatType statType, String detail) {
        return new ApiProblemException(code, statType, null, detail);
    }

    ProblemDetails toProblem(String instance, String traceId) {
        if (errors != null) {
            return ProblemDetails.validationFailure(code, statType, errors, instance, traceId);
        }
        return ProblemDetails.of(code, detail, instance, statType, traceId);
    }
}
