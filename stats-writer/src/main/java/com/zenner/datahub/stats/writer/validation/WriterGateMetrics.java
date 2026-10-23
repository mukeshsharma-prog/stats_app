package com.zenner.datahub.stats.writer.validation;

import com.zenner.datahub.stats.common.model.StatType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * What the Writer's gate observed about a delivery, per stat type.
 *
 * <p>Both counters are deliberately separate from
 * {@link com.zenner.datahub.stats.writer.retry.WriterFailureMetrics}. That class counts
 * what the retry arm <em>did</em>; this one counts what was <em>wrong with the
 * record</em>, and the two answer different operational questions.
 * {@code records_dead_lettered} is tagged with the root exception class, which cannot
 * separate "the payload does not match its schema" from "a table invariant was
 * violated" — and those call for different responses: the first is an upstream
 * conversation, the second is ours.</p>
 */
@Component
public class WriterGateMetrics {

    /** Deliveries the gate refused and sent straight to the DLQ, tagged by cause. */
    public static final String PAYLOAD_REJECTED = "records_payload_rejected";

    /** The payload did not match its per-type schema. An upstream conversation. */
    public static final String REASON_PAYLOAD_SCHEMA = "payload_schema";

    /**
     * The record's own {@code type} was not the type of the queue it arrived on. A
     * routing or binding fault, and <em>ours</em> — not upstream's.
     */
    public static final String REASON_TYPE_MISMATCH = "type_mismatch";

    /**
     * The record passed its schema but could not be turned into a row: an unknown
     * payload dialect, a counter that will not fit its column, a required member that
     * is absent by the time the mapper reads it.
     *
     * <p>It is counted here rather than as a <em>write</em> failure because no write is
     * attempted — the mapping happens before the transaction opens. The distinction is
     * operational, not pedantic: {@code records_write_failed} is a signal about the
     * <strong>database</strong>, and an operator who sees it climb reaches for the
     * database first. A payload nobody can map is upstream's shape, and the database is
     * perfectly healthy while it happens.</p>
     */
    public static final String REASON_UNMAPPABLE = "unmappable";

    /**
     * Deliveries whose upstream {@code status} was not exactly {@code ["conversion ok"]}.
     * These are <strong>stored</strong>; the counter is the only signal on our
     * side that Heimdal already knew the conversion had failed.
     */
    public static final String UPSTREAM_STATUS_NOT_OK = "records_upstream_status_not_ok";

    private final MeterRegistry meterRegistry;

    public WriterGateMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    /**
     * Registers this type's gate series up front so each exports at zero from startup.
     * A counter that first appears on its first event is absent from
     * the scrape until then, and {@code rate()} of an absent series is absent too — a
     * dashboard cannot tell "no rejections" from "this replica exports no such series",
     * and that is the distinction an operator reads it for. The {@code reason} set is
     * closed, so registering every value is three series, not a cardinality risk.
     */
    public void registerFor(StatType statType) {
        Objects.requireNonNull(statType, "statType");
        for (String reason : List.of(REASON_PAYLOAD_SCHEMA, REASON_TYPE_MISMATCH, REASON_UNMAPPABLE)) {
            payloadRejectedCounter(statType, reason);
        }
        upstreamStatusNotOkCounter(statType);
    }

    /**
     * One refusal at the gate, tagged with what caused it.
     *
     * <p>The {@code reason} tag is not decoration. "The payload does not match its
     * schema" is a conversation with Heimdal about a contract; "this record is on the
     * wrong queue" is a binding or routing fault on our side, and the two call for
     * different people and different fixes. Both throw
     * {@link com.zenner.datahub.stats.writer.retry.PermanentFailureException}, so
     * {@code records_dead_lettered{type,reason}} sees one class name for both and cannot
     * tell them apart — this counter is the only place the distinction survives. The tag
     * set is <strong>closed at the two constants above</strong>, so it cannot grow into a
     * cardinality problem the way a free-text reason would.</p>
     */
    public void payloadRejected(StatType statType, String reason) {
        payloadRejectedCounter(statType, reason).increment();
    }

    private Counter payloadRejectedCounter(StatType statType, String reason) {
        return Counter.builder(PAYLOAD_REJECTED)
                .description("Records refused by the Writer's gate and dead-lettered without a retry")
                .tag("type", statType.wireName())
                .tag("reason", reason)
                .register(meterRegistry);
    }

    /**
     * No label carries the {@code status} value itself. The vocabulary of failure
     * strings is upstream's and unknown to us, and a label sourced
     * from an unbounded vocabulary we do not control is a Prometheus cardinality bomb.
     */
    public void upstreamStatusNotOk(StatType statType) {
        upstreamStatusNotOkCounter(statType).increment();
    }

    private Counter upstreamStatusNotOkCounter(StatType statType) {
        return Counter.builder(UPSTREAM_STATUS_NOT_OK)
                .description(
                        "Records stored whose upstream status was not exactly "
                                + "[\"conversion ok\"]")
                .tag("type", statType.wireName())
                .register(meterRegistry);
    }
}
