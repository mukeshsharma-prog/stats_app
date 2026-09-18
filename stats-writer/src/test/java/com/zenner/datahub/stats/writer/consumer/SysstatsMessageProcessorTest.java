package com.zenner.datahub.stats.writer.consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.common.model.StatsEnvelope;
import com.zenner.datahub.stats.writer.persistence.RecordWriteResult;
import com.zenner.datahub.stats.writer.persistence.StatsBatchWriter;
import com.zenner.datahub.stats.writer.retention.RetentionGuard;
import com.zenner.datahub.stats.writer.retry.PermanentFailureException;
import com.zenner.datahub.stats.writer.validation.PayloadGate;
import com.zenner.datahub.stats.writer.validation.ValidatedRecord;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;

class SysstatsMessageProcessorTest {

    private static final byte[] RECORD =
            """
            {
              "uid":"sysstats-processor-test", "tenant":"tenant-a", "maintenant":"manager-a",
              "clsbox":"A1B2C3D4E5F6", "type":"sysstats",
              "ts":{"server":1787054120,"device":1787054100},
              "data":{}
            }
            """.getBytes(UTF_8);

    private final PayloadGate gate = mock(PayloadGate.class);
    private final RetentionGuard retentionGuard = mock(RetentionGuard.class);
    private final StatsBatchWriter writer = mock(StatsBatchWriter.class);
    private final SysstatsMessageProcessor processor =
            new SysstatsMessageProcessor(gate, retentionGuard, writer);

    @BeforeEach
    void admitEverythingByDefault() {
        when(retentionGuard.admit(eq(StatType.SYSSTATS), any())).thenReturn(true);
    }

    private static ValidatedRecord validated() throws Exception {
        StatsEnvelope envelope = new ObjectMapper().readValue(RECORD, StatsEnvelope.class);
        return ValidatedRecord.of(envelope, "v1");
    }

    @Test
    void claimsExactlyItsOwnType() {
        assertThat(processor.statType()).isEqualTo(StatType.SYSSTATS);
    }

    @Test
    void sendsEveryDeliveryThroughAOneElementList() throws Exception {
        when(gate.admit(eq(StatType.SYSSTATS), any())).thenReturn(validated());
        when(writer.write(anyList())).thenReturn(List.of(RecordWriteResult.inserted(0)));

        processor.process(StatType.SYSSTATS, new Message(RECORD));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ValidatedRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).write(captor.capture());
        assertThat(captor.getValue())
                .singleElement()
                .satisfies(record -> assertThat(record.envelope().uid()).isEqualTo("sysstats-processor-test"));
    }

    @Test
    void treatsADuplicateAsADurableSuccess() throws Exception {
        when(gate.admit(eq(StatType.SYSSTATS), any())).thenReturn(validated());
        when(writer.write(anyList())).thenReturn(List.of(RecordWriteResult.duplicateSuppressed(0)));

        processor.process(StatType.SYSSTATS, new Message(RECORD));

        verify(writer).write(anyList());
    }

    @Test
    void propagatesTheAttributedWriteFailureSoTheConsumerCannotAck() throws Exception {
        RuntimeException failure = new IllegalStateException("database transaction failed");
        when(gate.admit(eq(StatType.SYSSTATS), any())).thenReturn(validated());
        when(writer.write(anyList())).thenReturn(List.of(RecordWriteResult.failed(0, failure)));

        assertThatThrownBy(() -> processor.process(StatType.SYSSTATS, new Message(RECORD)))
                .isSameAs(failure);
    }

    @Test
    void aPayloadRejectionNeverReachesTheWriter() {
        PermanentFailureException rejected =
                new PermanentFailureException("Payload failed its per-type schema");
        when(gate.admit(eq(StatType.SYSSTATS), any())).thenThrow(rejected);

        assertThatThrownBy(() -> processor.process(StatType.SYSSTATS, new Message(RECORD)))
                .isSameAs(rejected);

        verify(writer, never()).write(anyList());
        verify(retentionGuard, never()).admit(any(), any());
    }

    @Test
    void aRecordPastItsRetentionWindowIsSkippedAndAcknowledged() throws Exception {
        when(gate.admit(eq(StatType.SYSSTATS), any())).thenReturn(validated());
        when(retentionGuard.admit(eq(StatType.SYSSTATS), any())).thenReturn(false);

        processor.process(StatType.SYSSTATS, new Message(RECORD));

        verify(writer, never()).write(anyList());
    }

    @Test
    void rejectsAnotherTypeBeforeValidatingOrWriting() {
        assertThatThrownBy(() -> processor.process(StatType.LORASTATS, new Message(RECORD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sysstats");

        verify(gate, never()).admit(any(), any());
        verify(retentionGuard, never()).admit(any(), any());
        verify(writer, never()).write(anyList());
    }
}
