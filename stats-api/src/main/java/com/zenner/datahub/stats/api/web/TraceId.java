package com.zenner.datahub.stats.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Mints a {@code traceId} per request, returns it as {@code X-Trace-Id}, and keeps it in
 * the MDC for every log line of the request (T-005, applied to the API).
 *
 * <p>The same key, header and format as Input ({@code IngestLoggingFilter}), so a support
 * request quoting a {@code traceId} from a problem body is looked up the same way in both
 * modules. The key is the MDC scope's alone: a log statement must never repeat it as a
 * key-value pair, or the ECS encoder drops the line (DL-150).</p>
 */
public final class TraceId extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";
    public static final String RESPONSE_HEADER = "X-Trace-Id";

    /** The current request's trace id, or {@code null} outside a request. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString();
        MDC.put(MDC_KEY, traceId);
        try {
            response.setHeader(RESPONSE_HEADER, traceId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
