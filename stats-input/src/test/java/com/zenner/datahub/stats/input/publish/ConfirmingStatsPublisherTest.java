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

    @Test
    @DisplayName("a broker NACK is not a publish")
    void nackIsNotConfirmed() {
        answerConfirmWith(new CorrelationData.Confirm(false, "broker said no"), null);

        assertThat(publisher.publish(StatType.LORASTATS, BODY))
                .isEqualTo(PublishOutcome.NOT_CONFIRMED);
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
    }

    @Test
    @DisplayName("a clean ACK with no return is the only path to CONFIRMED")
    void cleanAckIsConfirmed() {
        answerConfirmWith(new CorrelationData.Confirm(true, null), null);

        assertThat(publisher.publish(StatType.LORASTATS, BODY)).isEqualTo(PublishOutcome.CONFIRMED);
        assertThat(meterRegistry.find(IngestMetrics.UNROUTABLE).counter()).isNull();
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
