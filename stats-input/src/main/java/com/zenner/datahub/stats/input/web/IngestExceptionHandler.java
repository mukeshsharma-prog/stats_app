package com.zenner.datahub.stats.input.web;

import com.zenner.datahub.stats.common.error.ErrorCode;
import com.zenner.datahub.stats.common.error.FieldError;
import com.zenner.datahub.stats.common.error.FieldError.SafeMessage;
import com.zenner.datahub.stats.common.error.ProblemDetails;
import com.zenner.datahub.stats.input.ingest.IngestMetrics;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the two failures that happen <em>before</em> the envelope gate into the RFC 9457
 * bodies the contract promises (T-018).
 *
 * <p>Without this, both arrive at Spring's default handler and are answered with a body
 * that is not {@code application/problem+json} — a shape no caller has been told about,
 * on the two paths a caller is most likely to hit while integrating.</p>
 */
@RestControllerAdvice
public class IngestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(IngestExceptionHandler.class);

    private final IngestMetrics metrics;

    public IngestExceptionHandler(IngestMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** A chunked body that passed the limit while being read (see the size filter). */
    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<ProblemDetails> handlePayloadTooLarge(
            PayloadTooLargeException exception, HttpServletRequest request) {
        return payloadTooLarge(request);
    }

    /**
     * The body could not be read at all.
     *
     * <p>The cause decides the status, and the unwrapping is not decoration: a
     * {@link PayloadTooLargeException} thrown from the wrapped input stream reaches
     * Spring wrapped in this exception, and answering that 400 would tell Heimdal to
     * fix a record that is not malformed.</p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetails> handleUnreadableBody(
            HttpMessageNotReadableException exception, HttpServletRequest request) {

        if (NestedExceptionUtils.getMostSpecificCause(exception)
                instanceof PayloadTooLargeException) {
            return payloadTooLarge(request);
        }

        metrics.validationFailed(null);
        log.warn("Rejected an unreadable request body at the ingest boundary; nothing published");
        return problem(
                ProblemDetails.validationFailure(
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        List.of(FieldError.of("/", "unreadable_body", SafeMessage.INVALID_FIELD_TYPE)),
                        request.getRequestURI(),
                        null));
    }

    private ResponseEntity<ProblemDetails> payloadTooLarge(HttpServletRequest request) {
        metrics.requestTooLarge();
        log.warn(
                "Rejected a chunked request body that exceeded the configured limit while being "
                        + "read; nothing published");
        return problem(
                ProblemDetails.of(
                        ErrorCode.PAYLOAD_TOO_LARGE,
                        "The request body exceeds the configured maximum.",
                        request.getRequestURI()));
    }

    private static ResponseEntity<ProblemDetails> problem(ProblemDetails problem) {
        return ResponseEntity.status(problem.status())
                .contentType(MediaType.valueOf(ProblemDetails.CONTENT_TYPE))
                .body(problem);
    }
}
