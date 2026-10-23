package com.zenner.datahub.stats.input.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.common.error.ProblemDetails;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Writes an RFC 9457 body from a servlet filter, where {@code @RestController} return
 * values are not available.
 *
 * <p>It exists so a filter cannot answer with an empty body and a bare status code.
 * A rejected record is answered, not silently dropped, and a 413
 * with nothing in it tells the sender only that something is wrong somewhere.</p>
 */
public final class ProblemWriter {

    private final ObjectMapper objectMapper;

    public ProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public void write(HttpServletResponse response, ProblemDetails problem) throws IOException {
        response.setStatus(problem.status());
        response.setContentType(ProblemDetails.CONTENT_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), problem);
        response.flushBuffer();
    }
}
