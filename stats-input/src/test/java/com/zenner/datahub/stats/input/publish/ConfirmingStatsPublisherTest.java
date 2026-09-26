package com.zenner.datahub.stats.input.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.input.ingest.IngestMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Every ending other than a clean ack, with a mocked template.
 *
 * <p>These are the endings a real broker will not produce on demand — a nack, a
 * confirm that never arrives, a connection that refuses. Each one must reach
 * {@link PublishOutcome#NOT_CONFIRMED}, because each one becomes a 503 and Heimdal's
 * resend is the only thing that saves the record (STA-04A, STA-06A). The happy path
 * and the unroutable path are proved against a real broker instead, in
 * {@code IngestPublishIntegrationTest} — a mock asserting our own assumptions back to
 * us is worth nothing there.</p>
 */
class ConfirmingStatsPublisherTest {

    private static final byte[] BODY = "{\"uid\":\"publish-outcome-proof\"}".getBytes(StandardCharsets.UTF_8);

    private RabbitTemplate rabbitTemplate;
    private MeterRegistry meterRegistry;
    private ConfirmingStatsPublisher publisher;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        meterRegistry = new SimpleMeterRegistry();
        publisher = publisherWithConfirmTimeout(Duration.ofSeconds(5));
    }

    private ConfirmingStatsPublisher publisherWithConfirmTimeout(Duration confirmTimeout) {
        return new ConfirmingStatsPublisher(
                rabbitTemplate,
                new AmqpPublisherProperties("app_stats_input", confirmTimeout),
                new IngestMetrics(meterRegistry));
    }

    /**
     * T-050 / DL-147. The Writer computes {@code queue_oldest_message_age_seconds} from
     * the standard AMQP {@code timestamp} property, so every message Input publishes has
     * to carry one. A property rather than a header: it rides with the retry and DLQ
     * copies the way {@code correlationId} does, and the body stays byte-identical.
     */
    @Test
    @DisplayName("every published message carries its publish time as the AMQP timestamp")
    void stampsThePublishTimeOnTheMessage() {
        java.util.concurrent.atomic.AtomicReference<Message> sent = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(invocation -> {
                    sent.set(invocation.getArgument(2));
                    CorrelationData correlation = invocation.getArgument(3);
                    correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
                    return null;
                })
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        java.time.Instant before = java.time.Instant.now().minusSeconds(1);
        assertThat(publisher.publish(StatType.LORASTATS, BODY)).isEqualTo(PublishOutcome.CONFIRMED);
        java.time.Instant after = java.time.Instant.now().plusSeconds(1);

        assertThat(sent.get()).isNotNull();
        assertThat(sent.get().getMessageProperties().getTimestamp()).isNotNull();
        assertThat(sent.get().getMessageProperties().getTimestamp().toInstant())
                .isBetween(before, after);
        // The stamp is a property, never a change to the bytes (ADR-004).
        assertThat(sent.get().getBody()).isEqualTo(BODY);
    }

    @Test
    @DisplayName("a broker NACK is not a publish")
    void nackIsNotConfirmed() {
        answerConfirmWith(new CorrelationData.Confirm(false, "broker said no"), null);

        assertThat(publisher.publish(StatType.LORASTATS, BODY))
                .isEqualTo(PublishOutcome.NOT_CONFIRMED);
        assertThat(confirmTimings(IngestMetrics.OUTCOME_NACK)).isEqualTo(1L);
    }

    @Test
    @DisplayName("a confirm that never arrives times out and is answered 503, not awaited forever")
    void missingConfirmTimesOutWithinTheConfiguredBound() {
        // The template is a mock and completes nothing, which is exactly a broker that
        // has taken the message and gone quiet. The assertion that matters is the
        // ELAPSED TIME: "waits until the socket dies" also returns NOT_CONFIRMED and
        // would look identical in a test that only checked the outcome.
        ConfirmingStatsPublisher bounded = publisherWithConfirmTimeout(Duration.ofMillis(150));

        long startedAt = System.nanoTime();
        PublishOutcome outcome = bounded.publish(StatType.LORASTATS, BODY);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(outcome).isEqualTo(PublishOutcome.NOT_CONFIRMED);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        assertThat(confirmTimings(IngestMetrics.OUTCOME_TIMEOUT)).isEqualTo(1L);
    }

    @Test
    @DisplayName("an unreachable broker is answered 503 rather than thrown to the caller")
    void connectionFailureIsNotConfirmed() {
        // If this escaped as an exception it would become a 500 from a generic handler
        // with no counter behind it. Heimdal retries either way; we would be blind.
        doThrow(new AmqpConnectException(new java.net.ConnectException("refused")))
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        assertThat(publisher.publish(StatType.LORASTATS, BODY))
                .isEqualTo(PublishOutcome.NOT_CONFIRMED);
        assertThat(confirmTimings(IngestMetrics.OUTCOME_ERROR)).isEqualTo(1L);
    }

    @Test
    @DisplayName("an ACK carrying a returned message is a FAILED acceptance, not a success")
    void ackedButReturnedIsNotConfirmed() {
        // ADR-002 §9: the broker accepted it and no queue was bound to take it. This is
        // the case a publisher confirm alone cannot see.
        ReturnedMessage returned =
                new ReturnedMessage(
                        new Message(BODY, new MessageProperties()),
                        312,
                        "NO_ROUTE",
                        "app_stats_input",
                        "lorastats");
        answerConfirmWith(new CorrelationData.Confirm(true, null), returned);

        assertThat(publisher.publish(StatType.LORASTATS, BODY))
                .isEqualTo(PublishOutcome.NOT_CONFIRMED);
        assertThat(
                        meterRegistry
                                .counter(IngestMetrics.UNROUTABLE, "type", StatType.LORASTATS.wireName())
                                .count())
                .isEqualTo(1.0d);
        assertThat(confirmTimings(IngestMetrics.OUTCOME_RETURNED)).isEqualTo(1L);
    }

    @Test
    @DisplayName("a clean ACK with no return is the only path to CONFIRMED")
    void cleanAckIsConfirmed() {
        answerConfirmWith(new CorrelationData.Confirm(true, null), null);

        assertThat(publisher.publish(StatType.LORASTATS, BODY)).isEqualTo(PublishOutcome.CONFIRMED);
        assertThat(meterRegistry.find(IngestMetrics.UNROUTABLE).counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
        assertThat(confirmTimings(IngestMetrics.OUTCOME_CONFIRMED)).isEqualTo(1L);
    }

    /**
     * T-048 (DL-159): confirm latency, not throughput, is what turns a burst into 503s,
     * so every outcome is timed and the histogram for the happy path exists from
     * construction — a p99 panel over a series that appears on the first record is empty
     * for exactly the period it is wanted.
     */
    @Test
    @DisplayName("the confirmed histogram exists for every type before the first record")
    void theConfirmHistogramExportsFromConstruction() {
        for (StatType type : StatType.values()) {
            assertThat(meterRegistry.find(IngestMetrics.PUBLISH_CONFIRM)
                            .tag("type", type.wireName())
                            .tag("outcome", IngestMetrics.OUTCOME_CONFIRMED)
                            .timer())
                    .as("%s confirmed timer", type.wireName())
                    .isNotNull();
        }
    }

    private long confirmTimings(String outcome) {
        var timer = meterRegistry.find(IngestMetrics.PUBLISH_CONFIRM)
                .tag("type", StatType.LORASTATS.wireName())
                .tag("outcome", outcome)
                .timer();
        return timer == null ? 0L : timer.count();
    }

    private void answerConfirmWith(CorrelationData.Confirm confirm, ReturnedMessage returned) {
        doAnswer(
                        invocation -> {
                            CorrelationData correlation = invocation.getArgument(3);
                            if (returned != null) {
                                correlation.setReturned(returned);
                            }
                            correlation.getFuture().complete(confirm);
                            return null;
                        })
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
}
