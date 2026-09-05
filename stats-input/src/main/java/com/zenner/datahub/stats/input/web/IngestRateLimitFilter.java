package com.zenner.datahub.stats.input.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.common.error.ErrorCode;
import com.zenner.datahub.stats.common.error.ProblemDetails;
import com.zenner.datahub.stats.input.config.RequestLimitProperties;
import com.zenner.datahub.stats.input.ingest.IngestMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The per-instance ingest rate limit (STA-06B, T-018; DL-109).
 *
 * <p>A token bucket refilled at {@code stats.input.limits.max-requests-per-second} and
 * holding one second of burst. Excess requests are answered 429 with {@code Retry-After}
 * and counted as {@code requests_rate_limited}.</p>
 *
 * <p><strong>What this does and does not control.</strong> Heimdal agreed the 2× replay
 * cap on 4 Sep 2026 (Q13c, DL-109) and we cannot enforce it — an agreement is a
 * commitment, not a control. This filter and the 503 backpressure of rule 8 are the two
 * we own. It is deliberately <em>per instance</em>: the cluster ceiling is this value
 * times the replica count, and a shared limiter would put a network round trip and a
 * new dependency in front of every ingest. A 429 to Heimdal is a non-2xx, so the record
 * is resent — the limit sheds load, it does not drop records.</p>
 *
 * <p>It runs after authentication so that, once Q8a-2 lands, an unauthenticated caller
 * cannot spend a legitimate sender's budget.</p>
 */
public class IngestRateLimitFilter extends OncePerRequestFilter {

    /** After authentication (20), before the endpoint. */
    public static final int ORDER = 30;

    private static final Logger log = LoggerFactory.getLogger(IngestRateLimitFilter.class);

    private final TokenBucket bucket;
    private final int permitsPerSecond;
    private final IngestMetrics metrics;
    private final ProblemWriter problemWriter;

    public IngestRateLimitFilter(
            RequestLimitProperties properties, IngestMetrics metrics, ObjectMapper objectMapper) {
        this.permitsPerSecond =
                Objects.requireNonNull(properties, "properties").maxRequestsPerSecond();
        this.bucket = new TokenBucket(permitsPerSecond);
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.problemWriter = new ProblemWriter(objectMapper);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (bucket.tryAcquire()) {
            chain.doFilter(request, response);
            return;
        }

        metrics.rateLimited();
        log.warn(
                "Ingest rate limit reached: {} requests/second on this instance "
                        + "(stats.input.limits.max-requests-per-second). Answering 429; Heimdal "
                        + "resends on any non-2xx",
                permitsPerSecond);
        response.setHeader("Retry-After", "1");
        problemWriter.write(
                response,
                ProblemDetails.of(
                        ErrorCode.TOO_MANY_REQUESTS,
                        "This instance is at its configured ingest rate. Retry after 1 second.",
                        request.getRequestURI()));
    }

    /**
     * A token bucket, refilled continuously rather than on a schedule.
     *
     * <p>Boring on purpose: no scheduler, no dependency, no per-caller state. Per-caller
     * buckets would need a caller identity, and Q8a-2 has not yet given us one.</p>
     */
    private static final class TokenBucket {

        private final double permitsPerSecond;
        private final double capacity;

        private double tokens;
        private long lastRefillNanos;

        private TokenBucket(int permitsPerSecond) {
            this.permitsPerSecond = permitsPerSecond;
            this.capacity = permitsPerSecond;
            this.tokens = permitsPerSecond;
            this.lastRefillNanos = System.nanoTime();
        }

        private synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / (double) TimeUnit.SECONDS.toNanos(1);
            lastRefillNanos = now;
            tokens = Math.min(capacity, tokens + elapsedSeconds * permitsPerSecond);

            if (tokens < 1.0d) {
                return false;
            }
            tokens -= 1.0d;
            return true;
        }
    }
}
