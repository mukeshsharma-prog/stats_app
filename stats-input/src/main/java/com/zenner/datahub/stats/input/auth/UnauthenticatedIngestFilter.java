package com.zenner.datahub.stats.input.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The {@code stats.input.auth.mode=none} branch: it authenticates nothing and says so.
 *
 * <p>It exists as a real filter rather than as an absent one because the seam has to
 * be occupied. A mode that quietly registers no filter is indistinguishable at every
 * later reading from a mode whose filter failed to register, and the difference
 * between those two is whether the endpoint is open by decision or by accident.</p>
 *
 * <p>The startup WARN is emitted by {@link InputAuthConfiguration} — once, at the point
 * of selection. It is not logged per request: a line on every one of ~1,333 requests a
 * second is not a louder warning, it is a warning nobody can see.</p>
 */
public final class UnauthenticatedIngestFilter extends OncePerRequestFilter {

    /**
     * Runs after the request-size check and before the rate limit.
     *
     * <p>Size first because it is a header comparison that admits nothing, and because
     * an oversized body should be refused before anything reads it. Rate limiting after
     * authentication so that, once ingest is authenticated, an unauthenticated caller cannot spend
     * a legitimate sender's budget.</p>
     */
    public static final int ORDER = 20;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, response);
    }
}
