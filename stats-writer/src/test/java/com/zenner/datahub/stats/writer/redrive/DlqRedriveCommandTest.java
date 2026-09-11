package com.zenner.datahub.stats.writer.redrive;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.LongString;
import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.writer.retry.FailedDeliveryRouter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class DlqRedriveCommandTest {
    private static final StatType TYPE = StatType.LORASTATS;

    @Test
    void preservesFailureStateAndMarksTheRecordForOneManualAttempt() {
        GetResponse delivery = delivery(
                currentBody(),
                Map.of(
                        FailedDeliveryRouter.ATTEMPT_HEADER,
                        4,
                        FailedDeliveryRouter.FAILURE_HEADER,
                        "DataIntegrityViolationException",
                        FailedDeliveryRouter.PERMANENT_HEADER,
                        true,
                        "x-death",
                        "broker-history"));

        AMQP.BasicProperties result =
                DlqRedriveCommand.redriveProperties(delivery, options(), 7);

        assertThat(result.getHeaders())
                .doesNotContainKey("x-death")
                .containsEntry(FailedDeliveryRouter.ATTEMPT_HEADER, 4)
                .containsEntry(
                        FailedDeliveryRouter.FAILURE_HEADER,
                        "DataIntegrityViolationException")
                .containsEntry(FailedDeliveryRouter.PERMANENT_HEADER, true)
                .containsEntry(FailedDeliveryRouter.MANUAL_REDRIVE_HEADER, true)
                .containsEntry("x-stats-redrive-previous-attempt", 4)
                .containsEntry(
                        "x-stats-redrive-previous-failure", "DataIntegrityViolationException")
                .containsEntry("x-stats-redrive-change-id", "INC-123")
                .containsEntry("x-stats-redrive-sequence", 7)
                .containsKey("x-stats-redrive-at");
    }

    @Test
    void acknowledgesTheDlqOriginalOnlyAfterTheReplacementIsConfirmed() throws Exception {
        Channel channel = mock(Channel.class);
        GetResponse delivery = delivery(currentBody(), Map.of());
        when(channel.basicGet(AmqpTopology.deadLetterQueue(TYPE), false))
                .thenReturn(delivery)
                .thenReturn(null);
        when(channel.waitForConfirms(5_000)).thenReturn(true);

        int moved = DlqRedriveCommand.redrive(channel, options());

        assertThat(moved).isEqualTo(1);
        InOrder handoff = inOrder(channel);
        handoff.verify(channel)
                .basicPublish(
                        eq(AmqpTopology.INPUT_EXCHANGE),
                        eq(AmqpTopology.routingKey(TYPE)),
                        eq(true),
                        any(AMQP.BasicProperties.class),
                        any(byte[].class));
        handoff.verify(channel).waitForConfirms(5_000);
        handoff.verify(channel).basicAck(41L, false);
    }

    @Test
    void keepsTheDlqOriginalWhenTheReplacementIsNotConfirmed() throws Exception {
        Channel channel = mock(Channel.class);
        GetResponse delivery = delivery(currentBody(), Map.of());
        when(channel.basicGet(AmqpTopology.deadLetterQueue(TYPE), false)).thenReturn(delivery);
        when(channel.waitForConfirms(5_000)).thenReturn(false);

        assertThatThrownBy(() -> DlqRedriveCommand.redrive(channel, options()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("negatively acknowledged");

        verify(channel).basicNack(41L, false, true);
        verify(channel, never()).basicAck(41L, false);
    }

    @Test
    void doesNotPickUpARecordThatReturnsDuringTheSameInvocation() throws Exception {
        Channel channel = mock(Channel.class);
        GetResponse onlyRecord = delivery(currentBody(), Map.of());
        when(channel.basicGet(AmqpTopology.deadLetterQueue(TYPE), false))
                .thenReturn(onlyRecord);
        when(channel.waitForConfirms(5_000)).thenReturn(true);
        DlqRedriveCommand.Options largeBatch =
                new DlqRedriveCommand.Options(TYPE, 100, 2_667, 90, 5_000, "INC-123", true);

        assertThat(DlqRedriveCommand.redrive(channel, largeBatch)).isEqualTo(1);

        verify(channel, times(1)).basicGet(AmqpTopology.deadLetterQueue(TYPE), false);
    }

    @Test
    void refusesAnExpiredRecordAndLeavesItOnTheDlq() throws Exception {
        Channel channel = mock(Channel.class);
        byte[] expired = bodyAt(Instant.now().minus(91, ChronoUnit.DAYS).getEpochSecond());
        when(channel.basicGet(AmqpTopology.deadLetterQueue(TYPE), false))
                .thenReturn(delivery(expired, Map.of()));

        assertThatThrownBy(() -> DlqRedriveCommand.redrive(channel, options()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside the retention window");

        verify(channel).basicNack(41L, false, true);
        verify(channel, never())
                .basicPublish(anyString(), anyString(), anyBoolean(), any(), any(byte[].class));
    }

    @Test
    void replacesAParserDiagnosticThatCouldQuotePayloadBytes() throws Exception {
        Channel channel = mock(Channel.class);
        when(channel.basicGet(AmqpTopology.deadLetterQueue(TYPE), false))
                .thenReturn(delivery("{\"secret\":\"customer-value\"".getBytes(UTF_8), Map.of()));

        assertThatThrownBy(() -> DlqRedriveCommand.redrive(channel, options()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DLQ message is not valid JSON")
                .hasMessageNotContaining("customer-value");
    }

    @Test
    void safelyReadsAStringHeaderEncodedByTheBrokerAsAnAmqpLongString() {
        LongString encoded = mock(LongString.class);
        when(encoded.length()).thenReturn(18L);
        when(encoded.getBytes()).thenReturn("RehearsalRootCause".getBytes(UTF_8));
        GetResponse delivery = delivery(
                currentBody(), Map.of(FailedDeliveryRouter.FAILURE_HEADER, encoded));

        assertThat(DlqRedriveCommand.safeFailure(delivery)).isEqualTo("RehearsalRootCause");
    }

    @Test
    void enforcesTheDocumentedRateCapAndRejectsUnknownOptions() {
        assertThatThrownBy(
                        () -> DlqRedriveCommand.Options.parse(new String[] {
                            "--type=lorastats",
                            "--max-messages=1",
                            "--rate-per-second=2668",
                            "--retention-days=90",
                            "--change-id=INC-123",
                            "--root-cause-resolved=true"
                        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2667");

        assertThatThrownBy(
                        () -> DlqRedriveCommand.Options.parse(new String[] {
                            "--type=lorastats",
                            "--max-messages=1",
                            "--rate-per-second=10",
                            "--retention-days=90",
                            "--change-id=INC-123",
                            "--root-cause-resolved=true",
                            "--automatic=true"
                        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Required:");

        assertThatThrownBy(
                        () -> DlqRedriveCommand.Options.parse(new String[] {
                            "--type=lorastats",
                            "--max-messages=1",
                            "--rate-per-second=10",
                            "--retention-days=90",
                            "--change-id=INC-123"
                        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root-cause-resolved=true");
    }

    private static DlqRedriveCommand.Options options() {
        return new DlqRedriveCommand.Options(TYPE, 1, 2_667, 90, 5_000, "INC-123", true);
    }

    private static GetResponse delivery(byte[] body, Map<String, Object> headers) {
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .messageId("CLSCON-redrive-proof")
                .headers(headers)
                .deliveryMode(2)
                .build();
        return new GetResponse(new Envelope(41L, false, "", "source"), properties, body, 0);
    }

    private static byte[] currentBody() {
        return bodyAt(Instant.now().getEpochSecond());
    }

    private static byte[] bodyAt(long deviceTimestamp) {
        return ("{\"type\":\"lorastats\",\"ts\":{\"device\":" + deviceTimestamp + "}}")
                .getBytes(UTF_8);
    }
}
