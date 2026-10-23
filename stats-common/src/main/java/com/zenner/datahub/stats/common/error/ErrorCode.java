package com.zenner.datahub.stats.common.error;

import java.net.URI;

/**
 * Canonical machine-readable error catalogue for <strong>both</strong> published
 * contracts — {@code docs/contracts/openapi.yaml} for the read API and
 * {@code docs/contracts/heimdal-input-contract-v1.md} §2 for the ingest endpoint.
 *
 * <p>The wire code and HTTP status live together so response mapping cannot assign
 * a different status to the same error class. The problem type URI ends with the
 * exact wire code, which is the value callers match on.</p>
 *
 * <p><strong>Why one catalogue and not two.</strong> {@code payload_too_large}
 * and {@code too_many_requests} appear only in the inbound contract, so an
 * Input-local enum was the obvious alternative. It was rejected: two catalogues means
 * two type-URI bases, and a caller matching on {@code type} then has to know which
 * module answered before it can parse the error. The class was already named for
 * {@code openapi.yaml} alone, which is what made a second one look necessary — the
 * javadoc was the bug, not the enum.</p>
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
    /** Ingest only — contract §2. The body exceeds {@code stats.input.limits.max-request-bytes}. */
    PAYLOAD_TOO_LARGE("payload_too_large", "Payload too large", 413),
    /**
     * Ingest only — contract §2. The per-instance ingest rate limit, the only replay
     * control we actually own: Heimdal has agreed to cap replay at 2x nominal, and an
     * agreement is a commitment rather than a control.
     */
    TOO_MANY_REQUESTS("too_many_requests", "Too many requests", 429),
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
