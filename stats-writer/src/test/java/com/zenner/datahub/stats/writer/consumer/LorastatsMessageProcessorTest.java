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

class LorastatsMessageProcessorTest {

    private static final byte[] RECORD =
            """
            {
              "uid":"processor-test", "tenant":"tenant-a", "maintenant":"manager-a",
              "clsbox":"A1B2C3D4E5F6", "type":"lorastats",
              "ts":{"server":1787054120,"device":1787054100},
              "data":{}
            }
            """.getBytes(UTF_8);

    private final PayloadGate gate = mock(PayloadGate.class);
    private final RetentionGuard retentionGuard = mock(RetentionGuard.class);
    private final StatsBatchWriter writer = mock(StatsBatchWriter.class);
    private final LorastatsMessageProcessor processor =
            new LorastatsMessageProcessor(gate, retentionGuard, writer);

    @BeforeEach
    void admitEverythingByDefault() {
        // Every test but the late-arrival ones is about a record inside its window.
        when(retentionGuard.admit(eq(StatType.LORASTATS), any())).thenReturn(true);
    }

    private static ValidatedRecord validated() throws Exception {
        StatsEnvelope envelope =
                new ObjectMapper().readValue(RECORD, StatsEnvelope.class);
        return ValidatedRecord.of(envelope, "v1");
    }

    @Test
    void sendsEveryDeliveryThroughAOneElementList() throws Exception {
        when(gate.admit(eq(StatType.LORASTATS), any())).thenReturn(validated());
        when(writer.write(anyList())).thenReturn(List.of(RecordWriteResult.inserted(0)));

        processor.process(StatType.LORASTATS, new Message(RECORD));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ValidatedRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).write(captor.capture());
        assertThat(captor.getValue())
                .singleElement()
                .satisfies(record -> assertThat(record.envelope().uid()).isEqualTo("processor-test"));
    }

    @Test
    void treatsADuplicateAsADurableSuccess() throws Exception {
        when(gate.admit(eq(StatType.LORASTATS), any())).thenReturn(validated());
        when(writer.write(anyList()))
                .thenReturn(List.of(RecordWriteResult.duplicateSuppressed(0)));

        processor.process(StatType.LORASTATS, new Message(RECORD));

        verify(writer).write(anyList());
    }

    @Test
    void propagatesTheAttributedWriteFailureSoTheConsumerCannotAck() throws Exception {
        RuntimeException failure = new IllegalStateException("database transaction failed");
        when(gate.admit(eq(StatType.LORASTATS), any())).thenReturn(validated());
        when(writer.write(anyList())).thenReturn(List.of(RecordWriteResult.failed(0, failure)));

        assertThatThrownBy(() -> processor.process(StatType.LORASTATS, new Message(RECORD)))
                .isSameAs(failure);
    }

    /**
     * T-057, and the ordering is the assertion. A record that fails its payload schema
     * must not reach the writer at all: not one transaction is opened, and the failure
     * propagates so {@code LorastatsConsumer} routes the delivery instead of ACKing it.
     */
    @Test
    void aPayloadRejectionNeverReachesTheWriter() {
        PermanentFailureException rejected =
                new PermanentFailureException("Payload failed its per-type schema");
        when(gate.admit(eq(StatType.LORASTATS), any())).thenThrow(rejected);

        assertThatThrownBy(() -> processor.process(StatType.LORASTATS, new Message(RECORD)))
                .isSameAs(rejected);

        verify(writer, never()).write(anyList());
    }

    /**
     * T-026 / D5b. A record past its retention window is skipped, and "skipped" here means
     * the method RETURNS: LorastatsConsumer ACKs on a normal return, and an exception
     * would instead route a perfectly valid record into the retry arm and then a DLQ that
     * never expires — where every redrive would reach this same decision again, because
     * the record can only have got older.
     */
    @Test
    void aRecordPastItsRetentionWindowIsSkippedAndAcknowledged() throws Exception {
        when(gate.admit(eq(StatType.LORASTATS), any())).thenReturn(validated());
        when(retentionGuard.admit(eq(StatType.LORASTATS), any())).thenReturn(false);

        processor.process(StatType.LORASTATS, new Message(RECORD));

        verify(writer, never()).write(anyList());
    }

    /**
     * The gate runs first and the guard second, in that order. A record that is both too
     * old and malformed must dead-letter, where the schema mismatch can be diagnosed,
     * rather than being discarded as merely late.
     */
    @Test
    void validatesThePayloadBeforeCheckingTheRetentionWindow() {
        PermanentFailureException rejected =
                new PermanentFailureException("Payload failed its per-type schema");
        when(gate.admit(eq(StatType.LORASTATS), any())).thenThrow(rejected);

        assertThatThrownBy(() -> processor.process(StatType.LORASTATS, new Message(RECORD)))
                .isSameAs(rejected);

        verify(retentionGuard, never()).admit(any(), any());
    }

    @Test
    void rejectsAnotherTypeBeforeValidatingOrWriting() {
        assertThatThrownBy(() -> processor.process(StatType.NETSTATS, new Message(RECORD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lorastats");

        verify(gate, never()).admit(any(), any());
        verify(retentionGuard, never()).admit(any(), any());
        verify(writer, never()).write(anyList());
    }
}
