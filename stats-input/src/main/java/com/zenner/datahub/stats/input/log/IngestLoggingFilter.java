package com.zenner.datahub.stats.input.log;

import com.zenner.datahub.stats.input.config.IngestEndpointProperties;
import com.zenner.datahub.stats.input.config.IngestSource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes and clears correlation context around every ingest request.
 *
 * <p>It also resolves which ingest door the request came through and
 * publishes that once — as the {@code ingestSource} MDC key for every log line of the
 * request, and as a request attribute for the filters and the controller behind it, so
 * the path is matched in one place and every metric carries the same answer.</p>
 */
public final class IngestLoggingFilter extends OncePerRequestFilter {

    public static final int ORDER = 0;
    public static final String TRACE_ID_RESPONSE_HEADER = "X-Trace-Id";

    /** Request attribute holding the {@link IngestSource} this filter resolved. */
    public static final String SOURCE_ATTRIBUTE = IngestLoggingFilter.class.getName() + ".source";

    private final IngestEndpointProperties endpointProperties;

    public IngestLoggingFilter(IngestEndpointProperties endpointProperties) {
        this.endpointProperties = Objects.requireNonNull(endpointProperties, "endpointProperties");
    }

    /**
     * The door a request came through, as resolved by this filter — {@code null} when
     * the filter did not run for the request, which the callers label {@code unknown}.
     */
    public static IngestSource source(HttpServletRequest request) {
        Object attribute = request.getAttribute(SOURCE_ATTRIBUTE);
        return attribute instanceof IngestSource source ? source : null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try (IngestLogContext.Scope ignored = IngestLogContext.openRequest()) {
            response.setHeader(TRACE_ID_RESPONSE_HEADER, IngestLogContext.traceId());
            IngestSource source = endpointProperties.sourceOf(pathWithinContext(request));
            if (source != null) {
                request.setAttribute(SOURCE_ATTRIBUTE, source);
                IngestLogContext.source(source);
            }
            chain.doFilter(request, response);
        }
    }

    private static String pathWithinContext(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;
    }
}
