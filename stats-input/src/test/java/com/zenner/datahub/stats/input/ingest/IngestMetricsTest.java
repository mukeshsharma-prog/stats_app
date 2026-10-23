package com.zenner.datahub.stats.input.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.input.config.IngestSource;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The reporting-cycle phase histogram, and the device-clock correction counter. */
class IngestMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final IngestMetrics metrics = new IngestMetrics(registry);

    @Test
    void recordsTsServerModuloTheTenMinuteCycle() {
        // 1787833739 mod 600 = 539 -- the 29 Aug production record, 9 minutes into a cycle.
        metrics.tsServerCycleOffset(StatType.LORASTATS, IngestSource.INTERNAL, Instant.ofEpochSecond(1_787_833_739L));
        // An exact boundary is offset 0, not 600.
        metrics.tsServerCycleOffset(StatType.LORASTATS, IngestSource.INTERNAL, Instant.ofEpochSecond(1_787_833_800L));

        DistributionSummary summary = summary(StatType.LORASTATS);
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.max()).isEqualTo(539.0d);
        assertThat(summary.totalAmount()).isEqualTo(539.0d);
    }

    /**
     * The buckets are the contract: twenty cumulative thirty-second slots across the
     * cycle, so a synchronised fleet shows as one full slot and a jittering one as
     * twenty roughly equal ones. A percentile histogram would give latency-style
     * edges nobody can compare between days.
     */
    @Test
    void exportsTwentyThirtySecondBucketsAcrossTheCycle() {
        metrics.tsServerCycleOffset(StatType.SYSSTATS, IngestSource.INTERNAL, Instant.ofEpochSecond(45L));
        metrics.tsServerCycleOffset(StatType.SYSSTATS, IngestSource.INTERNAL, Instant.ofEpochSecond(599L));

        HistogramSnapshot snapshot = summary(StatType.SYSSTATS).takeSnapshot();
        double[] edges =
                Arrays.stream(snapshot.histogramCounts()).mapToDouble(CountAtBucket::bucket).toArray();
        assertThat(edges).hasSize(20);
        assertThat(edges[0]).isEqualTo(30.0d);
        assertThat(edges[19]).isEqualTo(600.0d);
        // Cumulative: the 45 s record is in every bucket from le=60 upward, the 599 s
        // record only in le=600.
        assertThat(countAt(snapshot, 30.0d)).isZero();
        assertThat(countAt(snapshot, 60.0d)).isEqualTo(1.0d);
        assertThat(countAt(snapshot, 570.0d)).isEqualTo(1.0d);
        assertThat(countAt(snapshot, 600.0d)).isEqualTo(2.0d);
    }

    @Test
    void labelsTheHistogramByTypeAndSourceAndNothingElse() {
        metrics.tsServerCycleOffset(StatType.OMSSTATS, IngestSource.INTERNAL, Instant.ofEpochSecond(10L));

        assertThat(summary(StatType.OMSSTATS).getId().getTags())
                .extracting(tag -> tag.getKey() + "=" + tag.getValue())
                .containsExactlyInAnyOrder("type=omsstats", "source=internal");
        assertThat(summary(StatType.OMSSTATS).getId().getBaseUnit()).isEqualTo("seconds");
    }

    @Test
    void countsHeimdalsTsDeviceCorrectionPerType() {
        metrics.tsDeviceCorrected(StatType.SYSSTATS, IngestSource.INTERNAL);
        metrics.tsDeviceCorrected(StatType.SYSSTATS, IngestSource.INTERNAL);

        assertThat(
                        registry.get(IngestMetrics.TS_DEVICE_CORRECTED)
                                .tag("type", "sysstats")
                                .tag("source", "internal")
                                .counter()
                                .count())
                .isEqualTo(2.0d);
        // The series for a type that has seen no event exists, at zero -- it is
        // registered at construction so a dashboard can tell "none" from "not exported".
        assertThat(
                        registry.get(IngestMetrics.TS_DEVICE_CORRECTED)
                                .tag("type", "lorastats")
                                .tag("source", "internal")
                                .counter()
                                .count())
                .isZero();
    }

    /**
     * The two ingest doors run identical rules, so the {@code source} label is the
     * only thing that makes the split observable. Every per-type counter carries it, a
     * request the logging filter never saw is {@code unknown} rather than unlabelled, and
     * the sum over the label is the total the series had before the split.
     */
    @Test
    void labelsEveryCounterBySourceAndSumsToTheOldTotal() {
        metrics.accepted(StatType.LORASTATS, IngestSource.INTERNAL);
        metrics.accepted(StatType.LORASTATS, IngestSource.INTERNAL);
        metrics.accepted(StatType.LORASTATS, IngestSource.EXTERNAL);
        metrics.validationFailed(null, null);
        metrics.requestTooLarge(IngestSource.EXTERNAL);
        metrics.rateLimited(IngestSource.INTERNAL);

        assertThat(count(IngestMetrics.ACCEPTED, "lorastats", "internal")).isEqualTo(2.0d);
        assertThat(count(IngestMetrics.ACCEPTED, "lorastats", "external")).isEqualTo(1.0d);
        assertThat(
                        registry.get(IngestMetrics.ACCEPTED).tag("type", "lorastats").counters().stream()
                                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                                .sum())
                .isEqualTo(3.0d);
        assertThat(count(IngestMetrics.VALIDATION_FAILED, "unknown", "unknown")).isEqualTo(1.0d);
        assertThat(
                        registry.get(IngestMetrics.REQUEST_TOO_LARGE)
                                .tag("source", "external")
                                .counter()
                                .count())
                .isEqualTo(1.0d);
        assertThat(
                        registry.get(IngestMetrics.RATE_LIMITED).tag("source", "internal").counter().count())
                .isEqualTo(1.0d);
    }

    /** {@code records_unroutable} is a fact about the broker, not the caller: no source label. */
    @Test
    void unroutableCarriesNoSourceLabel() {
        metrics.unroutable(StatType.SYSSTATS);

        assertThat(registry.get(IngestMetrics.UNROUTABLE).tag("type", "sysstats").counter().getId().getTags())
                .extracting(tag -> tag.getKey())
                .containsExactly("type");
    }

    private double count(String name, String type, String source) {
        return registry.get(name).tag("type", type).tag("source", source).counter().count();
    }

    private DistributionSummary summary(StatType type) {
        return registry.get(IngestMetrics.TS_SERVER_CYCLE_OFFSET)
                .tag("type", type.wireName())
                .summary();
    }

    private static double countAt(HistogramSnapshot snapshot, double bucket) {
        return Arrays.stream(snapshot.histogramCounts())
                .filter(count -> count.bucket() == bucket)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no bucket at " + bucket))
                .count();
    }
}
