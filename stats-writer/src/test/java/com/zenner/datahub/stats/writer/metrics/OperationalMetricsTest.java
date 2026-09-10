package com.zenner.datahub.stats.writer.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zenner.datahub.stats.writer.retention.RetentionProperties;
import com.zenner.datahub.stats.writer.retention.RetentionWindow;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;

/** T-027: the broker gauges distinguish unknown, empty and unavailable, and never do I/O on scrape. */
class OperationalMetricsTest {

    private final AmqpAdmin admin = mock(AmqpAdmin.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OperationalMetrics metrics =
            new OperationalMetrics(admin, window(90), registry, List.of(), "lorastats");

    private static RetentionWindow window(int days) {
        return new RetentionWindow(
                new RetentionProperties(
                        RetentionProperties.PartitionGranularity.DAILY,
                        7,
                        "-",
                        days,
                        Map.of(),
                        Duration.ZERO),
                Clock.systemUTC());
    }

    private double value(String name) {
        return registry.get(name).tag("type", "lorastats").gauge().value();
    }

    @Test
    void distinguishesUnknownEmptyBacklogAndUnavailableAndRecovers() {
        assertThat(value(OperationalMetrics.QUEUE_DEPTH)).as("nothing observed yet").isNaN();
        assertThat(value(OperationalMetrics.LAST_SUCCESS)).isZero();

        when(admin.getQueueInfo("app_statsstore.lorastats"))
                .thenReturn(new QueueInformation("app_statsstore.lorastats", 7, 2));
        when(admin.getQueueInfo("app_statsstore.lorastats.dlq"))
                .thenReturn(new QueueInformation("app_statsstore.lorastats.dlq", 3, 0));
        metrics.poll();
        assertThat(value(OperationalMetrics.QUEUE_DEPTH)).isEqualTo(7);
        assertThat(value(OperationalMetrics.DLQ_DEPTH)).isEqualTo(3);
        assertThat(value(OperationalMetrics.CONSUMER_AVAILABILITY)).isEqualTo(2);
        assertThat(value(OperationalMetrics.COLLECTION_SUCCESS)).isEqualTo(1);
        double lastSuccess = value(OperationalMetrics.LAST_SUCCESS);
        assertThat(lastSuccess).isPositive();

        // The broker cannot report: NaN and a dropped success flag, NOT a zero -- and the
        // last-success time stays put so staleness is measurable.
        when(admin.getQueueInfo("app_statsstore.lorastats")).thenReturn(null);
        metrics.poll();
        assertThat(value(OperationalMetrics.QUEUE_DEPTH)).isNaN();
        assertThat(value(OperationalMetrics.CONSUMER_AVAILABILITY)).isNaN();
        assertThat(value(OperationalMetrics.COLLECTION_SUCCESS)).isZero();
        assertThat(value(OperationalMetrics.LAST_SUCCESS)).isEqualTo(lastSuccess);

        // Recovery to a genuinely empty queue with no consumers reads as zero, with the flag up.
        when(admin.getQueueInfo("app_statsstore.lorastats"))
                .thenReturn(new QueueInformation("app_statsstore.lorastats", 0, 0));
        metrics.poll();
        assertThat(value(OperationalMetrics.QUEUE_DEPTH)).isZero();
        assertThat(value(OperationalMetrics.CONSUMER_AVAILABILITY)).isZero();
        assertThat(value(OperationalMetrics.COLLECTION_SUCCESS)).isEqualTo(1);

        // A scrape reads the cache: no broker call.
        clearInvocations(admin);
        registry.getMeters().forEach(meter -> meter.measure().forEach(measurement -> measurement.getValue()));
        verifyNoInteractions(admin);
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .containsExactly(Tag.of("type", "lorastats")));
    }

    @Test
    void retentionPeriodIsTheWindowTheSweepAndGuardUse() {
        // DL-133: one cutoff. This gauge reads RetentionWindow, so it cannot advertise a
        // different number of days than the one deleting data.
        assertThat(value(OperationalMetrics.RETENTION_PERIOD_DAYS)).isEqualTo(90);

        SimpleMeterRegistry other = new SimpleMeterRegistry();
        new OperationalMetrics(admin, window(30), other, List.of(), "lorastats");
        assertThat(other.get(OperationalMetrics.RETENTION_PERIOD_DAYS).gauge().value()).isEqualTo(30);
    }

    @Test
    void servesNothingForATypeWhoseQueueThisContextDidNotDeclare() {
        SimpleMeterRegistry all = new SimpleMeterRegistry();
        new OperationalMetrics(admin, window(90), all, List.of(), "all");
        assertThat(all.getMeters()).as("`all` mode with no declared queues").isEmpty();
    }
}
