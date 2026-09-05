package com.zenner.datahub.stats.input.ingest;

import com.zenner.datahub.stats.common.model.StatType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Per-type Input counters (STA-27A, T-027).
 *
 * <p>Every counter is labelled by stat type, including the failures. A total that
 * cannot be broken down by type cannot answer the only question worth asking during
 * an incident — <em>which</em> type started failing.</p>
 *
 * <p>Records whose type could not be read are labelled {@link #UNKNOWN_TYPE} rather
 * than left unlabelled: an absent series and a zero series are indistinguishable on
 * a dashboard, and a flood of unparseable bodies is precisely the case where the
 * series must exist.</p>
 */
@Component
public class IngestMetrics {

    public static final String ACCEPTED = "records_accepted";
    public static final String VALIDATION_FAILED = "records_validation_failed";
    public static final String CLOCK_SKEW_REJECTED = "records_clock_skew_rejected";
    public static final String CONVERTER_TS_PRESENT = "records_converter_ts_present";
    public static final String PUBLISH_FAILED = "records_publish_failed";
    public static final String UNROUTABLE = "records_unroutable";

    /**
     * The two counters below are deliberately <strong>not</strong> labelled by stat
     * type, which is the one exception to the rule above. Both fire before the body
     * has been read, so the type is genuinely unknown rather than unreadable — and
     * {@link #UNKNOWN_TYPE} already means "we parsed and could not tell". Reusing it
     * here would merge a flood of oversized requests into the series an operator
     * watches for malformed records.
     */
    public static final String REQUEST_TOO_LARGE = "requests_too_large";

    /** See {@link #REQUEST_TOO_LARGE} for why this carries no type label. */
    public static final String RATE_LIMITED = "requests_rate_limited";

    /** Type label used when the record's own {@code type} could not be resolved. */
    public static final String UNKNOWN_TYPE = "unknown";

    private final MeterRegistry meterRegistry;

    public IngestMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    /** A record that passed the gate, was published, and was publisher-confirmed. */
    public void accepted(StatType statType) {
        counter(ACCEPTED, "Records accepted and publisher-confirmed", statType).increment();
    }

    /** Envelope schema failure, unknown stat type, or a body that was not JSON. */
    public void validationFailed(StatType statType) {
        counter(VALIDATION_FAILED, "Records rejected at the envelope gate with 400", statType)
                .increment();
    }

    /** Q14a: {@code ts.device} beyond the configured tolerance (ADR-003 D9). */
    public void clockSkewRejected(StatType statType) {
        counter(CLOCK_SKEW_REJECTED, "Records rejected because ts.device leads ts.server", statType)
                .increment();
    }

    /**
     * T-059. Expected to read ~100% today; its value is showing us the day Heimdal
     * stops sending {@code ts.converter} (ADR-007 §2, "accept, ignore, and meter").
     */
    public void converterTimestampPresent(StatType statType) {
        counter(CONVERTER_TS_PRESENT, "Records carrying ts.converter, which is never read", statType)
                .increment();
    }

    /** Publish or confirm failed, so the caller was told 503 and will retry. */
    public void publishFailed(StatType statType) {
        counter(PUBLISH_FAILED, "Records not published or not confirmed, answered with 503", statType)
                .increment();
    }

    /**
     * ADR-002 §9: the broker acked the publish and no queue was bound to receive it.
     *
     * <p>{@link #publishFailed(StatType)} increments as well, and that is intended —
     * this counter is the <em>cause</em>, that one is the <em>outcome</em>. Their ratio
     * is what separates "the broker is down" from "a queue is missing", which are the
     * same 503 to Heimdal and completely different incidents to us.</p>
     */
    public void unroutable(StatType statType) {
        counter(UNROUTABLE, "Records the broker acked but no queue was bound to take", statType)
                .increment();
    }

    /** T-018: the body exceeded {@code stats.input.limits.max-request-bytes}; 413. */
    public void requestTooLarge() {
        untypedCounter(REQUEST_TOO_LARGE, "Requests rejected with 413 before the body was read")
                .increment();
    }

    /** T-018: the per-instance ingest rate limit rejected the request; 429. */
    public void rateLimited() {
        untypedCounter(RATE_LIMITED, "Requests rejected with 429 by the ingest rate limit")
                .increment();
    }

    private Counter untypedCounter(String name, String description) {
        return Counter.builder(name).description(description).register(meterRegistry);
    }

    private Counter counter(String name, String description, StatType statType) {
        return Counter.builder(name)
                .description(description)
                .tag("type", statType == null ? UNKNOWN_TYPE : statType.wireName())
                .register(meterRegistry);
    }
}
