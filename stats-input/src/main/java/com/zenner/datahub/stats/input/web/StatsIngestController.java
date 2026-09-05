package com.zenner.datahub.stats.input.web;

import com.zenner.datahub.stats.common.error.ErrorCode;
import com.zenner.datahub.stats.common.error.FieldError;
import com.zenner.datahub.stats.common.error.FieldError.SafeMessage;
import com.zenner.datahub.stats.common.error.ProblemDetails;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.input.config.IngestEndpointProperties;
import com.zenner.datahub.stats.input.ingest.EnvelopeGate;
import com.zenner.datahub.stats.input.ingest.GateResult;
import com.zenner.datahub.stats.input.ingest.IngestMetrics;
import com.zenner.datahub.stats.input.publish.PublishOutcome;
import com.zenner.datahub.stats.input.publish.StatsPublisher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Heimdal ingest endpoint (STA-01A, STA-03A, STA-03B; T-013).
 *
 * <p>Three steps, in this order, and the order is load-bearing:</p>
 *
 * <ol>
 *   <li><strong>Envelope only</strong> — {@link EnvelopeGate}. A failure is a 400 and
 *       nothing is published (rule 7). The per-type payload is deliberately not
 *       checked here; that gate belongs to the Writer, where a failure is a
 *       redrivable dead-letter (ADR-007 §5).</li>
 *   <li><strong>Clock skew</strong> — Q14a. Cross-field, so it cannot live in the
 *       schema.</li>
 *   <li><strong>Publish, then confirm</strong> — the response is a 2xx only after the
 *       broker confirms (STA-04A, rule 8). Anything else is 503, because Heimdal
 *       owns retry and only retries on a non-2xx.</li>
 * </ol>
 *
 * <p>The body is taken as {@code byte[]} rather than bound to a typed record, so the
 * bytes published are exactly the bytes received (ADR-004, T-016). Binding would
 * round-trip through Jackson and quietly re-encode the record.</p>
 *
 * <p>Non-POST methods on this path return 405 from Spring's own handler mapping, with
 * no side effect.</p>
 */
@RestController
public class StatsIngestController {

    private static final Logger log = LoggerFactory.getLogger(StatsIngestController.class);

    private final EnvelopeGate gate;
    private final StatsPublisher publisher;
    private final IngestMetrics metrics;
    private final IngestEndpointProperties endpointProperties;

    public StatsIngestController(
            EnvelopeGate gate,
            StatsPublisher publisher,
            IngestMetrics metrics,
            IngestEndpointProperties endpointProperties) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.endpointProperties = Objects.requireNonNull(endpointProperties, "endpointProperties");
    }

    @PostMapping(
            path = "${stats.input.endpoint.path}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProblemDetails> ingest(
            @RequestBody(required = false) byte[] body, HttpServletRequest request) {

        String instance = request.getRequestURI();

        if (body == null || body.length == 0) {
            metrics.validationFailed(null);
            log.warn("Rejected an empty request body at the Input gate; nothing published");
            return problem(
                    ProblemDetails.validationFailure(
                            ErrorCode.INVALID_PARAMETER,
                            null,
                            List.of(
                                    FieldError.of(
                                            "/", "empty_body", SafeMessage.MANDATORY_FIELD_ABSENT)),
                            instance,
                            null));
        }

        GateResult result = gate.evaluate(body, instance);

        if (result instanceof GateResult.Rejected rejected) {
            switch (rejected.reason()) {
                case CLOCK_SKEW_EXCEEDED -> metrics.clockSkewRejected(rejected.statType());
                case MALFORMED_JSON, INVALID_ENVELOPE -> metrics.validationFailed(rejected.statType());
            }
            return problem(rejected.problem());
        }

        GateResult.Accepted accepted = (GateResult.Accepted) result;
        StatType statType = accepted.statType();

        // ADR-007 §2: ts.converter is accepted, preserved, never read — and metered,
        // so we can see the day Heimdal stops sending it (T-059).
        if (accepted.converterTimestampPresent()) {
            metrics.converterTimestampPresent(statType);
        }

        PublishOutcome outcome = publisher.publish(statType, body);
        if (outcome != PublishOutcome.CONFIRMED) {
            metrics.publishFailed(statType);
            log.error(
                    "Record passed validation but was not publisher-confirmed: type={}; "
                            + "answering 503 so Heimdal retries (STA-04A, STA-06A)",
                    statType.wireName());
            return problem(
                    ProblemDetails.of(
                            ErrorCode.SERVICE_UNAVAILABLE,
                            "The record was not accepted. Retry.",
                            instance,
                            statType));
        }

        metrics.accepted(statType);
        return ResponseEntity.status(endpointProperties.successStatus()).build();
    }

    private static ResponseEntity<ProblemDetails> problem(ProblemDetails problem) {
        return ResponseEntity.status(problem.status())
                .contentType(MediaType.valueOf(ProblemDetails.CONTENT_TYPE))
                .body(problem);
    }
}
