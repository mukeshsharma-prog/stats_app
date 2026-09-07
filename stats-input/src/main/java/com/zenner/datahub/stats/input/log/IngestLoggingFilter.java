package com.zenner.datahub.stats.input.log;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Establishes and clears correlation context around every ingest request. */
public final class IngestLoggingFilter extends OncePerRequestFilter {

    public static final int ORDER = 0;
    public static final String TRACE_ID_RESPONSE_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try (IngestLogContext.Scope ignored = IngestLogContext.openRequest()) {
            response.setHeader(TRACE_ID_RESPONSE_HEADER, IngestLogContext.traceId());
            chain.doFilter(request, response);
        }
    }
}
