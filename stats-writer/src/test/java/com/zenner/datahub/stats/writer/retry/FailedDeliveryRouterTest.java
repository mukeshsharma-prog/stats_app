package com.zenner.datahub.stats.writer.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.core.JsonParseException;
import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.model.StatType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The routing decision and the ACK ordering it protects (T-024, ADR-005).
 *
 * <p>The broker's own behaviour — that a tier's TTL really does bounce a message back
 * to the main queue — is proved against a real broker in
 * {@code RetryAndDeadLetterIntegrationTest}. What is proved here is the half a broker
 * cannot show us: which destination each failure picks, that the attempt counter
 * survives the round trip, and that an unconfirmed hand-off throws instead of
 * returning.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class FailedDeliveryRouterTest {

    private static final byte[] BODY = "{\"uid\":\"retry-routing-proof\"}".getBytes(StandardCharsets.UTF_8);

    private RabbitTemplate rabbitTemplate;
    private MeterRegistry meterRegistry;
    private FailedDeliveryRouter router;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        meterRegistry = new SimpleMeterRegistry();
        router =
                new FailedDeliveryRouter(
                        rabbitTemplate,
                        properties(Duration.ofSeconds(5)),
                        new FailureClassifier(),
                        new WriterFailureMetrics(meterRegistry));
        confirmEveryPublish();
    }

    private static WriterAmqpProperties properties(Duration confirmTimeout) {
        return new WriterAmqpProperties(
                new WriterAmqpProperties.Retry(
                        Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofMinutes(30)),
                3,
                confirmTimeout);
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "1, 2", "2, 3"})
    @DisplayName("each failed attempt goes to its own tier, one attempt per tier")
    void attemptsMapOneToOneOntoTiers(int previousAttempts, int expectedTier) {
        router.route(StatType.LORASTATS, delivery(previousAttempts), new IllegalStateException("db"));

        // Retry queues are holding queues with no binding: a message is placed on one
        // directly through the default exchange and leaves only when its TTL expires.
        verify(rabbitTemplate)
                .send(
                        eq(""),
                        eq(AmqpTopology.retryQueue(StatType.LORASTATS, expectedTier)),
                        any(Message.class),
                        any(CorrelationData.class));
        assertThat(
                        meterRegistry
                                .counter(
                                        WriterFailureMetrics.RETRIED,
                                        "type",
                                        StatType.LORASTATS.wireName(),
                                        "tier",
                                        Integer.toString(expectedTier))
                                .count())
                .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("a fourth failure retires the record to the DLQ, not to a fourth tier")
    void exhaustedRetriesReachTheDeadLetterQueue() {
        router.route(StatType.LORASTATS, delivery(3), new IllegalStateException("db"));

        verify(rabbitTemplate)
                .send(
                        eq(AmqpTopology.DEAD_LETTER_EXCHANGE),
                        eq(AmqpTopology.deadLetterRoutingKey(StatType.LORASTATS)),
                        any(Message.class),
                        any(CorrelationData.class));
        assertThat(deadLetterCount("IllegalStateException")).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("a permanent failure skips all three tiers")
    void permanentFailureGoesStraightToTheDeadLetterQueue() {
        // 35 and a half minutes of queue time saved per record, and at 115.2m/day a
        // systematic permanent failure would otherwise be three times the broker traffic
        // before the DLQ depth alert can even fire.
        router.route(
                StatType.LORASTATS,
                delivery(0),
                new DataIntegrityViolationException("clsbox check violated"));

        verify(rabbitTemplate)
                .send(
                        eq(AmqpTopology.DEAD_LETTER_EXCHANGE),
                        anyString(),
                        any(Message.class),
                        any(CorrelationData.class));
        assertThat(deadLetterCount("DataIntegrityViolationException")).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("the copy keeps the original bytes and gains only headers")
    void bodyIsRepublishedVerbatim() {
        // ADR-004 has to survive the retry loop: a redriven record must still collide on
        // (ts_device, uid), which is what makes ADR-003's duplicate suppression correct.
        router.route(StatType.LORASTATS, delivery(1), new IllegalStateException("db"));

        Message published = capturePublished();
        assertThat(published.getBody()).isEqualTo(BODY);
        assertThat(published.getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(published.getMessageProperties().getHeaders())
                .containsEntry(FailedDeliveryRouter.ATTEMPT_HEADER, 2)
                .containsEntry(FailedDeliveryRouter.FAILURE_HEADER, "IllegalStateException");
    }

    @Test
    @DisplayName("the failure header carries a class name, never an exception message")
    void failureHeaderNeverCarriesPayloadText() {
        // STA-03A: a JDBC or validation message routinely quotes the offending value,
        // and a broker header is displayed by ops tooling like any other field.
        router.route(
                StatType.LORASTATS,
                delivery(0),
                new IllegalStateException("uid=CLSCON-secret tenant=mzc clsbox=04B64810652B"));

        assertThat(capturePublished().getMessageProperties().getHeaders())
                .containsEntry(FailedDeliveryRouter.FAILURE_HEADER, "IllegalStateException");
    }

    @Test
    @DisplayName("an unconfirmed hand-off throws, so the caller cannot ACK the original")
    void unconfirmedHandOffThrows() {
        doAnswer(
                        invocation -> {
                            CorrelationData correlation = invocation.getArgument(3);
                            correlation.getFuture().complete(new CorrelationData.Confirm(false, "no"));
                            return null;
                        })
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        assertThatExceptionOfType(UnconfirmedHandoffException.class)
                .isThrownBy(
                        () ->
                                router.route(
                                        StatType.LORASTATS, delivery(0), new IllegalStateException("db")));
    }

    @Test
    @DisplayName("an unroutable hand-off throws — a missing retry queue is not an ACK")
    void returnedHandOffThrows() {
        doAnswer(
                        invocation -> {
                            CorrelationData correlation = invocation.getArgument(3);
                            correlation.setReturned(
                                    new ReturnedMessage(
                                            new Message(BODY, new MessageProperties()),
                                            312,
                                            "NO_ROUTE",
                                            "",
                                            AmqpTopology.retryQueue(StatType.LORASTATS, 1)));
                            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
                            return null;
                        })
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        assertThatExceptionOfType(UnconfirmedHandoffException.class)
                .isThrownBy(
                        () ->
                                router.route(
                                        StatType.LORASTATS, delivery(0), new IllegalStateException("db")));
    }

    @Test
    @DisplayName("a broker that cannot be reached throws rather than silently succeeding")
    void brokerFailureThrows() {
        doThrow(new AmqpConnectException(new java.net.ConnectException("refused")))
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        assertThatExceptionOfType(UnconfirmedHandoffException.class)
                .isThrownBy(
                        () ->
                                router.route(
                                        StatType.LORASTATS, delivery(0), new IllegalStateException("db")));
    }

    @Test
    @DisplayName("a confirm that never arrives is bounded and throws")
    void missingConfirmTimesOut() {
        FailedDeliveryRouter bounded =
                new FailedDeliveryRouter(
                        rabbitTemplate,
                        properties(Duration.ofMillis(120)),
                        new FailureClassifier(),
                        new WriterFailureMetrics(meterRegistry));
        // A mock that completes nothing is a broker that took the publish and went quiet.
        doAnswer(invocation -> null)
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        long startedAt = System.nanoTime();
        assertThatExceptionOfType(UnconfirmedHandoffException.class)
                .isThrownBy(
                        () ->
                                bounded.route(
                                        StatType.LORASTATS, delivery(0), new IllegalStateException("db")));
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("malformed JSON is permanent — the bytes will not change on redelivery")
    void malformedBodyIsPermanent() {
        router.route(
                StatType.LORASTATS,
                delivery(0),
                new RuntimeException("wrapped", new JsonParseException(null, "unexpected token")));

        verify(rabbitTemplate)
                .send(
                        eq(AmqpTopology.DEAD_LETTER_EXCHANGE),
                        anyString(),
                        any(Message.class),
                        any(CorrelationData.class));
    }


    @Test
    @DisplayName("neither log line carries the driver's DETAIL values — guard P2, end to end")
    void logLinesCarryNoRowValues(CapturedOutput output) {
        // The unit-level proof is SafeFailureTest; this is the one that would have caught
        // the original defect, because it asserts on what actually reached the log rather
        // than on what was passed to the helper. Both paths are exercised: a retry (WARN)
        // and a dead-letter (ERROR).
        String leakedUid = "CLSCON-11d3ec4c-f6e1-403a-a9ef-b6f5fe909eee";
        java.sql.SQLException driver =
                new java.sql.SQLException(
                        "ERROR: duplicate key value violates unique constraint \"lorastats_pkey\"\n"
                                + "  Detail: Key (ts_device, uid)=(1787833739, "
                                + leakedUid
                                + ") already exists.",
                        "23505");

        router.route(
                StatType.LORASTATS,
                delivery(0),
                new IllegalStateException("transient", driver));
        router.route(
                StatType.LORASTATS,
                delivery(0),
                new DataIntegrityViolationException("could not execute statement", driver));

        assertThat(output)
                .doesNotContain(leakedUid)
                .doesNotContain("1787833739")
                .doesNotContain("Detail")
                .doesNotContain("duplicate key value");
        // And the diagnostic that matters did survive both lines.
        assertThat(output).contains("sqlState=23505");
    }

    private double deadLetterCount(String reason) {
        return meterRegistry
                .counter(
                        WriterFailureMetrics.DEAD_LETTERED,
                        "type",
                        StatType.LORASTATS.wireName(),
                        "reason",
                        reason)
                .count();
    }

    private Message capturePublished() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate)
                .send(anyString(), anyString(), captor.capture(), any(CorrelationData.class));
        return captor.getValue();
    }

    private void confirmEveryPublish() {
        doAnswer(
                        invocation -> {
                            CorrelationData correlation = invocation.getArgument(3);
                            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
                            return null;
                        })
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    /** A delivery that has already been handed on {@code previousAttempts} times. */
    private static Message delivery(int previousAttempts) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(7L);
        if (previousAttempts > 0) {
            properties.setHeader(FailedDeliveryRouter.ATTEMPT_HEADER, previousAttempts);
        }
        return new Message(BODY, properties);
    }
}
