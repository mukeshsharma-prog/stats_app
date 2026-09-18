package com.zenner.datahub.stats.writer.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.writer.retry.FailedDeliveryRouter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/** T-050 / DL-147: the oldest-message-age gauge, sampled at delivery. */
class QueueAgeMetricsTest {

    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final QueueAgeMetrics metrics =
            new QueueAgeMetrics(registry, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void isNaNUntilSomethingHasBeenObserved() {
        // "Not observed yet" and "empty" must not read the same (DL-137).
        metrics.register(StatType.LORASTATS);

        assertThat(gauge(StatType.LORASTATS).value()).isNaN();
        assertThat(gauge(StatType.LORASTATS).getId().getBaseUnit()).isEqualTo("seconds");
    }

    @Test
    void reportsTheAgeOfADeliveryFromItsPublishTimestamp() {
        double observed =
                metrics.observe(StatType.LORASTATS, delivery(NOW.minusSeconds(342), null));

        assertThat(observed).isEqualTo(342.0d);
        assertThat(gauge(StatType.LORASTATS).value()).isEqualTo(342.0d);
    }

    @Test
    void clampsAMessageFromTheFutureToZeroRatherThanReportingANegativeBacklog() {
        // Clock skew between the Input and Writer hosts is a clock problem, not a queue
        // that is somehow ahead of itself.
        assertThat(metrics.observe(StatType.LORASTATS, delivery(NOW.plusSeconds(7), null)))
                .isZero();
    }

    @Test
    void ignoresADeliveryWithNoTimestampAndLeavesTheGaugeAlone() {
        metrics.observe(StatType.LORASTATS, delivery(NOW.minusSeconds(10), null));

        assertThat(metrics.observe(StatType.LORASTATS, new Message(new byte[0], new MessageProperties())))
                .isNaN();
        assertThat(gauge(StatType.LORASTATS).value()).isEqualTo(10.0d);
    }

    /**
     * A message back from a retry tier has waited exactly that tier's delay by design.
     * Counting it would make every transient database failure look like the Writer
     * falling behind; the retry arm has its own counters.
     */
    @Test
    void ignoresARetryReentryIdentifiedByTheAttemptHeader() {
        metrics.observe(StatType.SYSSTATS, delivery(NOW.minusSeconds(3), null));

        assertThat(metrics.observe(StatType.SYSSTATS, delivery(NOW.minusSeconds(1800), 1)))
                .isNaN();
        assertThat(gauge(StatType.SYSSTATS).value()).isEqualTo(3.0d);
    }

    @Test
    void anEmptyQueueHasNoOldestMessageSoTheGaugeReadsZero() {
        metrics.observe(StatType.LORASTATS, delivery(NOW.minusSeconds(600), null));

        metrics.observedEmpty(StatType.LORASTATS);

        assertThat(gauge(StatType.LORASTATS).value()).isZero();
    }

    @Test
    void keepsOneSeriesPerTypeAndOnlyTheTypeLabel() {
        metrics.observe(StatType.LORASTATS, delivery(NOW.minusSeconds(1), null));
        metrics.observe(StatType.SYSSTATS, delivery(NOW.minusSeconds(2), null));

        assertThat(gauge(StatType.LORASTATS).value()).isEqualTo(1.0d);
        assertThat(gauge(StatType.SYSSTATS).value()).isEqualTo(2.0d);
        assertThat(gauge(StatType.SYSSTATS).getId().getTags())
                .extracting(tag -> tag.getKey() + "=" + tag.getValue())
                .containsExactly("type=sysstats");
    }

    private Gauge gauge(StatType type) {
        return registry.get(QueueAgeMetrics.OLDEST_MESSAGE_AGE).tag("type", type.wireName()).gauge();
    }

    private static Message delivery(Instant published, Integer attempt) {
        MessageProperties properties = new MessageProperties();
        properties.setTimestamp(Date.from(published));
        if (attempt != null) {
            properties.setHeader(FailedDeliveryRouter.ATTEMPT_HEADER, attempt);
        }
        return new Message("{}".getBytes(), properties);
    }
}
