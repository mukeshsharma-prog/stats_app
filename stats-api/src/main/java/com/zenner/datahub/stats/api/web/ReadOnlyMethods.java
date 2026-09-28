package com.zenner.datahub.stats.api.web;

import com.zenner.datahub.stats.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rule 1, STA-32/STA-32A (T-043): every method but GET on {@code /v1/**} is a 405 with
 * {@code Allow: GET} and no side effect — on every route, known or not.
 *
 * <p><strong>Why a filter, and why before authentication.</strong> Left to Spring MVC, a
 * non-GET on a mapped path is a 405 but on an unmapped one a 404; left to Spring Security,
 * an unauthenticated one is a 401 and — with CSRF on — an authenticated POST is a 403.
 * Three different answers to "may I write here?", where the contract gives one. The
 * method policy discloses nothing (it is published in the contract) and touches nothing,
 * so it is answered first and identically for every caller. The request never reaches
 * the security chain, a controller or the database.</p>
 */
public final class ReadOnlyMethods extends OncePerRequestFilter {

    private static final String API_ROOT = "/v1";

    private final ProblemResponses problems;

    public ReadOnlyMethods(ProblemResponses problems) {
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (HttpMethod.GET.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals(API_ROOT) || path.startsWith(API_ROOT + "/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HttpHeaders.ALLOW, HttpMethod.GET.name());
        problems.write(
                request,
                response,
                ErrorCode.METHOD_NOT_ALLOWED,
                "The Stats API is read-only. Only GET is supported.");
    }
}
