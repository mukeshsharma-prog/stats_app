package com.zenner.datahub.stats.writer.retry;

import com.zenner.datahub.stats.common.model.StatType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Per-type counters for the retry and dead-letter arm (STA-27A, T-024).
 *
 * <p>{@code records_dead_lettered} is tagged with the reason as well as the type,
 * because "the DLQ is filling" and "the DLQ is filling <em>with one kind of
 * failure</em>" call for different actions, and ADR-002 §12 makes disposition manual
 * within 14 days — an operator triaging by hand needs the breakdown before they open
 * a single message. The reason is a class name, never an exception message
 * (STA-03A).</p>
 */
@Component
public class WriterFailureMetrics {

    public static final String RETRIED = "records_retried";
    public static final String DEAD_LETTERED = "records_dead_lettered";

    private final MeterRegistry meterRegistry;

    public WriterFailureMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    /** A delivery handed to retry tier {@code tier}. Tier is a tag, not five metrics. */
    public void retried(StatType statType, int tier) {
        Counter.builder(RETRIED)
                .description("Records routed to a retry tier after a failed write")
                .tag("type", statType.wireName())
                .tag("tier", Integer.toString(tier))
                .register(meterRegistry)
                .increment();
    }

    /** A delivery retired to the type's DLQ — retries exhausted, or permanent. */
    public void deadLettered(StatType statType, String reason) {
        Counter.builder(DEAD_LETTERED)
                .description("Records routed to the per-type dead-letter queue")
                .tag("type", statType.wireName())
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }
}
