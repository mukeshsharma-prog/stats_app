package com.zenner.datahub.stats.input.ingest;

import com.zenner.datahub.stats.common.error.ProblemDetails;
import com.zenner.datahub.stats.common.model.StatType;
import java.time.Instant;
import java.util.Objects;

/**
 * The outcome of Input's envelope gate: either an accepted record with its resolved
 * stat type, or a rejection carrying the RFC 9457 body to return.
 *
 * <p>A rejection always carries a body. STA-03A forbids dropping a bad record
 * silently, and "return 400 with nothing in it" is the same failure wearing a status
 * code.</p>
 */
public sealed interface GateResult {

    /**
     * The envelope is valid and the clock skew is inside tolerance. The record has
     * not been published yet — acceptance is only complete after publisher confirm
     * (STA-04A).
     */
    record Accepted(
            StatType statType,
            boolean converterTimestampPresent,
            /**
             * The record's own {@code ts.server} — Heimdal's receipt clock, already
             * validated as an epoch-second integer. Carried out so the controller can
             * meter where in the ten-minute cycle the record arrived (T-050, DL-147).
             */
            Instant tsServer,
            /** {@code meta.tsDeviceCorrected == true}: Heimdal rewrote {@code ts.device} (DL-146). */
            boolean tsDeviceCorrected)
            implements GateResult {
        public Accepted {
            Objects.requireNonNull(statType, "statType");
            Objects.requireNonNull(tsServer, "tsServer");
        }
    }

    /**
     * The record is rejected at the HTTP boundary and <strong>nothing is
     * published</strong> (STA-03A, rule 7).
     *
     * <p>{@code statType} is null when the type itself was unreadable, which is why
     * the metric label falls back to {@code unknown} rather than being omitted — a
     * missing series and a zero series look identical on a dashboard.</p>
     */
    record Rejected(ProblemDetails problem, StatType statType, RejectionReason reason)
            implements GateResult {
        public Rejected {
            Objects.requireNonNull(problem, "problem");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Why a record was rejected, so each reason can be metered separately. */
    enum RejectionReason {
        /** The body was not parseable JSON at all. */
        MALFORMED_JSON,
        /** The envelope schema rejected it, or the stat type is unknown. */
        INVALID_ENVELOPE,
        /** {@code ts.device} is beyond the configured tolerance (Q14a). */
        CLOCK_SKEW_EXCEEDED
    }
}
