package com.zenner.datahub.stats.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.common.error.ErrorCode;
import com.zenner.datahub.stats.common.error.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;

/**
 * Writes RFC 9457 bodies from the places a controller advice cannot reach — the servlet
 * filters and Spring Security's entry point and access-denied handler (T-044).
 *
 * <p>Every problem this API returns is built from the shared {@link ErrorCode}
 * catalogue, so a 401 cannot carry a 403's type URI and the URI spelling is the one
 * Input already ships (underscores — the DL-145 residual, closed in DL-162).</p>
 */
public final class ProblemResponses {

    private final ObjectMapper objectMapper;

    public ProblemResponses(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** A problem with no per-field detail, for the current request. */
    public void write(
            HttpServletRequest request, HttpServletResponse response, ErrorCode code, String detail)
            throws IOException {
        write(response, ProblemDetails.of(code, detail, request.getRequestURI(), null, TraceId.current()));
    }

    public void write(HttpServletResponse response, ProblemDetails problem) throws IOException {
        response.setStatus(problem.status());
        response.setContentType(ProblemDetails.CONTENT_TYPE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
