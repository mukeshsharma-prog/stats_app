package com.zenner.datahub.stats.input.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.common.model.StatType;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** T-050 / DL-147: the reporting-cycle phase histogram, and the DL-146 correction counter. */
class IngestMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final IngestMetrics metrics = new IngestMetrics(registry);

    @Test
    void recordsTsServerModuloTheTenMinuteCycle() {
        // 1787833739 mod 600 = 539 -- the 29 Aug production record, 9 minutes into a cycle.
        metrics.tsServerCycleOffset(StatType.LORASTATS, Instant.ofEpochSecond(1_787_833_739L));
        // An exact boundary is offset 0, not 600.
        metrics.tsServerCycleOffset(StatType.LORASTATS, Instant.ofEpochSecond(1_787_833_800L));

        DistributionSummary summary = summary(StatType.LORASTATS);
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.max()).isEqualTo(539.0d);
        assertThat(summary.totalAmount()).isEqualTo(539.0d);
    }

    /**
     * The buckets are the contract: twenty cumulative thirty-second slots across the
     * cycle, so a synchronised fleet shows as one full slot and a jittering one as
     * twenty roughly equal ones (DL-104). A percentile histogram would give latency-style
     * edges nobody can compare between days.
     */
    @Test
    void exportsTwentyThirtySecondBucketsAcrossTheCycle() {
        metrics.tsServerCycleOffset(StatType.SYSSTATS, Instant.ofEpochSecond(45L));
        metrics.tsServerCycleOffset(StatType.SYSSTATS, Instant.ofEpochSecond(599L));

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
    void labelsTheHistogramByTypeAndNothingElse() {
        metrics.tsServerCycleOffset(StatType.OMSSTATS, Instant.ofEpochSecond(10L));

        assertThat(summary(StatType.OMSSTATS).getId().getTags())
                .extracting(tag -> tag.getKey() + "=" + tag.getValue())
                .containsExactly("type=omsstats");
        assertThat(summary(StatType.OMSSTATS).getId().getBaseUnit()).isEqualTo("seconds");
    }

    @Test
    void countsHeimdalsTsDeviceCorrectionPerType() {
        metrics.tsDeviceCorrected(StatType.SYSSTATS);
        metrics.tsDeviceCorrected(StatType.SYSSTATS);

        assertThat(
                        registry.get(IngestMetrics.TS_DEVICE_CORRECTED)
                                .tag("type", "sysstats")
                                .counter()
                                .count())
                .isEqualTo(2.0d);
        assertThat(registry.find(IngestMetrics.TS_DEVICE_CORRECTED).tag("type", "lorastats").counter())
                .isNull();
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
