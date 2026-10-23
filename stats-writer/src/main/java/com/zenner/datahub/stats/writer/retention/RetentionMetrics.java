package com.zenner.datahub.stats.writer.retention;

import com.zenner.datahub.stats.common.model.StatType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * What retention did, and what it decided about a record.
 *
 * <p>These are deliberately separate from the gate and failure-arm counters. Those answer
 * "what was wrong with this record" and "what did the retry arm do"; these answer "is
 * retention still running, and is anything arriving too late to keep" — different
 * questions, different responders, and one of them is a storage problem rather than a data
 * problem.</p>
 *
 * <h2>The gauge is the one that catches a stuck sweep</h2>
 *
 * <p>{@link #OLDEST_PARTITION_AGE_DAYS} exists because the two counters cannot report an
 * absence. A retention job that stops working stops incrementing
 * {@link #PARTITIONS_DROPPED}, and a counter that has stopped moving looks exactly like a
 * counter with nothing to do. The age gauge climbs instead: past the configured window it
 * is unambiguous, and it is what the alert reads.</p>
 *
 * <h2>Never observed is NaN, never zero</h2>
 *
 * <p>Both gauges are {@code NaN} until the first completed sweep on this replica. The
 * first stack run (21 Sep 2026) found the daily pass had not run for 29 hours — the host
 * had slept through the cron slot — and the age gauge read {@code 0.0}: a healthy number,
 * from a sweep that had never happened. A gauge at zero is indistinguishable from a
 * partition zero days old; a gauge at {@code NaN} says "nobody has looked". The broker
 * gauges follow the same rule. {@link #LAST_SWEEP_TIMESTAMP} is the companion
 * that makes "no sweep for a day" alertable at all: only the replica that
 * wins the lock sets it, so a rule aggregates with {@code max by (type)}.</p>
 */
@Component
public class RetentionMetrics {

    /**
     * Records skipped because their {@code ts.device} was already past the retention
     * window on arrival. Deliberate data loss, by a signed decision — which
     * is exactly why it is counted and alerted rather than merely logged.
     */
    public static final String EXPIRED_ON_ARRIVAL = "records_expired_on_arrival";

    /** Partitions detached and dropped by a normal retention sweep. */
    public static final String PARTITIONS_DROPPED = "retention_partitions_dropped";

    /**
     * Tables found already detached from their parent and dropped by the sweep.
     *
     * <p>Its own series rather than a tag on {@link #PARTITIONS_DROPPED}, because a
     * non-zero value means something specific: a previous run died between
     * {@code DETACH CONCURRENTLY} and {@code DROP TABLE}, leaving a day of rows occupying
     * disk that nothing would ever reclaim. Folded into the ordinary counter it would read
     * as retention working normally.</p>
     */
    public static final String ORPHANS_DROPPED = "retention_orphans_dropped";

    /**
     * Age in whole days of the oldest live partition, as at the last completed sweep.
     * Climbs when retention stops working; sits just below the configured window when it
     * is healthy.
     */
    public static final String OLDEST_PARTITION_AGE_DAYS = "retention_oldest_partition_age_days";

    /**
     * Unix time of the last completed sweep on this replica, {@code NaN} until there has
     * been one. The series an alert reads to say "the sweep has stopped" — the age gauge
     * cannot, because a sweep that never ran leaves it exactly where it was.
     */
    public static final String LAST_SWEEP_TIMESTAMP = "retention_last_sweep_timestamp_seconds";

    private final MeterRegistry meterRegistry;
    private final Map<StatType, AtomicReference<Double>> oldestPartitionAge = new ConcurrentHashMap<>();
    private final Map<StatType, AtomicReference<Double>> lastSweep = new ConcurrentHashMap<>();

    public RetentionMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    /**
     * Registers this deployment's series at zero, before anything has happened to them.
     *
     * <p>A counter that first appears on its first occurrence is absent exactly when
     * someone goes looking — and "has anything expired on arrival?" is a question whose
     * answer must be readable as <em>zero</em>, not as a missing series. The dialect
     * counters were registered up front for the same reason.</p>
     */
    public void registerFor(StatType type) {
        Objects.requireNonNull(type, "type");
        expiredOnArrivalCounter(type);
        partitionsDroppedCounter(type);
        orphansDroppedCounter(type);
        oldestPartitionAgeHolder(type);
        lastSweepHolder(type);
    }

    /** One record skipped, not stored, and acknowledged. */
    public void expiredOnArrival(StatType type) {
        expiredOnArrivalCounter(type).increment();
    }

    /** One partition detached and dropped. */
    public void partitionDropped(StatType type) {
        partitionsDroppedCounter(type).increment();
    }

    /** One already-detached leftover dropped by the sweep. */
    public void orphanDropped(StatType type) {
        orphansDroppedCounter(type).increment();
    }

    /**
     * Publishes the age of the oldest live partition after a sweep.
     *
     * <p>Reported by the sweep rather than sampled at scrape time on purpose: a gauge that
     * queried {@code pg_inherits} on every Prometheus scrape would put a catalogue query on
     * the monitoring path, and it would keep reporting a healthy number from a replica
     * whose sweep never ran.</p>
     */
    public void oldestPartitionAgeDays(StatType type, int ageInDays) {
        oldestPartitionAgeHolder(type).set((double) ageInDays);
    }

    /** A sweep ran to completion on this replica at {@code epochSeconds}. */
    public void sweepCompleted(StatType type, long epochSeconds) {
        lastSweepHolder(type).set((double) epochSeconds);
    }

    private Counter expiredOnArrivalCounter(StatType type) {
        return Counter.builder(EXPIRED_ON_ARRIVAL)
                .description(
                        "Records skipped because ts.device was already past the retention "
                                + "window on arrival; metered, logged and acknowledged, never "
                                + "dead-lettered")
                .tag("type", type.wireName())
                .register(meterRegistry);
    }

    private Counter partitionsDroppedCounter(StatType type) {
        return Counter.builder(PARTITIONS_DROPPED)
                .description("Daily partitions detached and dropped by retention")
                .tag("type", type.wireName())
                .register(meterRegistry);
    }

    private Counter orphansDroppedCounter(StatType type) {
        return Counter.builder(ORPHANS_DROPPED)
                .description(
                        "Already-detached partition tables dropped by the sweep; non-zero "
                                + "means an earlier run died between DETACH and DROP")
                .tag("type", type.wireName())
                .register(meterRegistry);
    }

    private AtomicReference<Double> oldestPartitionAgeHolder(StatType type) {
        return oldestPartitionAge.computeIfAbsent(
                type,
                key -> {
                    AtomicReference<Double> holder = new AtomicReference<>(Double.NaN);
                    Gauge.builder(OLDEST_PARTITION_AGE_DAYS, holder, AtomicReference::get)
                            .description(
                                    "Age in days of the oldest live partition at the last "
                                            + "completed sweep; climbs when retention stops; "
                                            + "NaN until the first sweep on this replica")
                            .tag("type", key.wireName())
                            .register(meterRegistry);
                    return holder;
                });
    }

    private AtomicReference<Double> lastSweepHolder(StatType type) {
        return lastSweep.computeIfAbsent(
                type,
                key -> {
                    AtomicReference<Double> holder = new AtomicReference<>(Double.NaN);
                    Gauge.builder(LAST_SWEEP_TIMESTAMP, holder, AtomicReference::get)
                            .description(
                                    "Unix time of the last completed retention sweep on this "
                                            + "replica; NaN until there has been one. Aggregate "
                                            + "across replicas with max by (type)")
                            .tag("type", key.wireName())
                            .register(meterRegistry);
                    return holder;
                });
    }
}
