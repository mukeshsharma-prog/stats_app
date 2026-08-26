package com.zenner.datahub.stats.common.error;

import java.net.URI;

/**
 * Canonical machine-readable error catalogue from {@code docs/contracts/openapi.yaml}.
 *
 * <p>The wire code and HTTP status live together so response mapping cannot assign
 * a different status to the same error class. The problem type URI ends with the
 * exact wire code, which is the value callers match on.</p>
 */
public enum ErrorCode {
    UNKNOWN_STAT_TYPE("unknown_stat_type", "Unknown stat type", 400),
    INVALID_PARAMETER("invalid_parameter", "Invalid request parameter", 400),
    INVALID_TIME_RANGE("invalid_time_range", "Invalid time range", 400),
    QUERY_WINDOW_EXCEEDED("query_window_exceeded", "Query window exceeded", 400),
    INVALID_CURSOR("invalid_cursor", "Invalid cursor", 400),
    UNAUTHORIZED("unauthorized", "Unauthorized", 401),
    FORBIDDEN("forbidden", "Forbidden", 403),
    RECORD_NOT_FOUND("record_not_found", "Record not found", 404),
    METHOD_NOT_ALLOWED("method_not_allowed", "Method not allowed", 405),
    SERVICE_UNAVAILABLE("service_unavailable", "Service unavailable", 503);

    public static final String TYPE_URI_BASE = "https://stats.example/errors";

    private final String wireCode;
    private final String title;
    private final int status;

    ErrorCode(String wireCode, String title, int status) {
        this.wireCode = wireCode;
        this.title = title;
        this.status = status;
    }

    public String wireCode() {
        return wireCode;
    }

    public String title() {
        return title;
    }

    public int status() {
        return status;
    }

    public URI typeUri() {
        return URI.create(TYPE_URI_BASE + "/" + wireCode);
    }
}
