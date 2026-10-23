package com.zenner.datahub.stats.input.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.common.error.ErrorCode;
import com.zenner.datahub.stats.common.error.ProblemDetails;
import com.zenner.datahub.stats.input.config.RequestLimitProperties;
import com.zenner.datahub.stats.input.ingest.IngestMetrics;
import com.zenner.datahub.stats.input.log.IngestLoggingFilter;
import com.zenner.datahub.stats.input.log.IngestLogContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a body larger than {@code stats.input.limits.max-request-bytes} with 413
 * (contract §2).
 *
 * <p><strong>Why this is not a container setting.</strong> The obvious implementation
 * is {@code server.tomcat.max-http-form-post-size}, and it does nothing here: that
 * limit applies to form content, and Heimdal sends {@code application/json}. A limit
 * that is configured, documented, and never fires is worse than no limit, because
 * nobody looks at it again.</p>
 *
 * <p>Two paths, because HTTP has two. A declared {@code Content-Length} is checked
 * before a byte is read. A chunked body has no declared length, so the stream is
 * wrapped and the limit applied as it is consumed — see {@link PayloadTooLargeException}
 * for why that half has to travel as an exception.</p>
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    /** First in the chain: a header comparison that admits nothing and reads nothing. */
    public static final int ORDER = 10;

    private static final Logger log = LoggerFactory.getLogger(RequestSizeLimitFilter.class);

    private final long maxBytes;
    private final IngestMetrics metrics;
    private final ProblemWriter problemWriter;

    public RequestSizeLimitFilter(
            RequestLimitProperties properties, IngestMetrics metrics, ObjectMapper objectMapper) {
        this.maxBytes = Objects.requireNonNull(properties, "properties").maxRequestBytes().toBytes();
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.problemWriter = new ProblemWriter(objectMapper);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxBytes) {
            reject(request, response, declaredLength);
            return;
        }

        if (declaredLength >= 0) {
            chain.doFilter(request, response);
            return;
        }

        chain.doFilter(new LimitedBodyRequest(request, maxBytes), response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, long declaredLength)
            throws IOException {
        metrics.requestTooLarge(IngestLoggingFilter.source(request));
        IngestLogContext.errorCode(ErrorCode.PAYLOAD_TOO_LARGE);
        // The declared length is ours, not the sender's payload, so logging it discloses
        // nothing.
        log.atWarn()
                .addKeyValue("event", "request_too_large")
                .addKeyValue("declaredBytes", declaredLength)
                .addKeyValue("limitBytes", maxBytes)
                .log(
                        "Rejected an oversized request body at the ingest boundary; nothing was "
                                + "read and nothing published");
        problemWriter.write(
                response,
                ProblemDetails.of(
                        ErrorCode.PAYLOAD_TOO_LARGE,
                        "The request body exceeds the configured maximum of " + maxBytes + " bytes.",
                        request.getRequestURI(),
                        null,
                        IngestLogContext.traceId()));
    }

    /** Applies the limit to a body whose length was never declared. */
    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private final long limit;

        private LimitedBodyRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {

                private long read;

                @Override
                public int read() throws IOException {
                    int value = delegate.read();
                    if (value != -1 && ++read > limit) {
                        throw new PayloadTooLargeException(limit);
                    }
                    return value;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int count = delegate.read(buffer, offset, length);
                    if (count > 0 && (read += count) > limit) {
                        throw new PayloadTooLargeException(limit);
                    }
                    return count;
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    delegate.setReadListener(readListener);
                }
            };
        }
    }
}
