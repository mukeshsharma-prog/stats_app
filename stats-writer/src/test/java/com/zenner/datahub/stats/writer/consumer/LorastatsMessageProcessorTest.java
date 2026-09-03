package com.zenner.datahub.stats.writer.consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.writer.persistence.RecordWriteResult;
import com.zenner.datahub.stats.writer.persistence.StatsBatchWriter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;

class LorastatsMessageProcessorTest {

    private static final byte[] VALID_RECORD =
            """
            {
              "uid":"processor-test", "tenant":"tenant-a", "maintenant":"manager-a",
              "clsbox":"A1B2C3D4E5F6", "type":"lorastats",
              "ts":{"server":1787054120,"device":1787054100},
              "data":{}
            }
            """.getBytes(UTF_8);

    private final StatsBatchWriter writer = mock(StatsBatchWriter.class);
    private final LorastatsMessageProcessor processor =
            new LorastatsMessageProcessor(new ObjectMapper(), writer);

    @Test
    void sendsEveryDeliveryThroughAOneElementList() throws Exception {
        when(writer.write(anyList())).thenReturn(List.of(RecordWriteResult.inserted(0)));

        processor.process(StatType.LORASTATS, new Message(VALID_RECORD));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.zenner.datahub.stats.common.model.StatsEnvelope>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(writer).write(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue())
                .singleElement()
                .satisfies(
                        envelope ->
                                org.assertj.core.api.Assertions.assertThat(envelope.uid())
                                        .isEqualTo("processor-test"));
    }

    @Test
    void treatsADuplicateAsADurableSuccess() throws Exception {
        when(writer.write(anyList()))
                .thenReturn(List.of(RecordWriteResult.duplicateSuppressed(0)));

        processor.process(StatType.LORASTATS, new Message(VALID_RECORD));

        verify(writer).write(anyList());
    }

    @Test
    void propagatesTheAttributedWriteFailureSoTheConsumerCannotAck() {
        RuntimeException failure = new IllegalStateException("database transaction failed");
        when(writer.write(anyList()))
                .thenReturn(List.of(RecordWriteResult.failed(0, failure)));

        assertThatThrownBy(
                        () ->
                                processor.process(
                                        StatType.LORASTATS, new Message(VALID_RECORD)))
                .isSameAs(failure);
    }

    @Test
    void rejectsAnotherTypeBeforeWriting() {
        assertThatThrownBy(
                        () ->
                                processor.process(
                                        StatType.NETSTATS, new Message(VALID_RECORD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lorastats");
    }
}
