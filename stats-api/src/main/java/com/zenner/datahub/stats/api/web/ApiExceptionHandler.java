package com.zenner.datahub.stats.api.web;

import com.zenner.datahub.stats.api.security.CallerTenant.TenantUnavailableException;
import com.zenner.datahub.stats.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the read path's failures to the contract's problems (T-044; RFC 9457).
 *
 * <p>Bodies are written straight to the response rather than returned for content
 * negotiation: a caller that sent {@code Accept: application/json} must still receive
 * its {@code application/problem+json} error, not a 406 in place of it.</p>
 *
 * <p><strong>Nothing from the database reaches a caller or a log line.</strong> A
 * {@code PSQLException} message can quote a row ({@code DETAIL: Key (...)=(...)}); only the
 * exception's type is logged, as the Writer does (DL-124).</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ProblemResponses problems;

    public ApiExceptionHandler(ProblemResponses problems) {
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    @ExceptionHandler(ApiProblemException.class)
    public void problem(
            ApiProblemException problem, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        problems.write(response, problem.toProblem(request.getRequestURI(), TraceId.current()));
    }

    /** Unreachable behind {@code ReadAccess}; a 403, never a query without a tenant. */
    @ExceptionHandler(TenantUnavailableException.class)
    public void noTenant(HttpServletRequest request, HttpServletResponse response) throws IOException {
        problems.write(
                request,
                response,
                ErrorCode.FORBIDDEN,
                "The token does not grant the required role or carries no usable tenant claim.");
    }

    /** PostgreSQL unreachable, or a transient failure: the read is safe to retry. */
    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class})
    public void unavailable(
            RuntimeException failure, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        LOG.atWarn()
                .addKeyValue("event", "read_dependency_unavailable")
                .addKeyValue("exceptionType", failure.getClass().getName())
                .log("Read not served: the database is unavailable");
        problems.write(
                request,
                response,
                ErrorCode.SERVICE_UNAVAILABLE,
                "The request was not served. Retry after the indicated interval.");
    }
}
