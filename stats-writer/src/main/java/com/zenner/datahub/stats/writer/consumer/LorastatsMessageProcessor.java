package com.zenner.datahub.stats.writer.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.common.model.StatsEnvelope;
import com.zenner.datahub.stats.writer.persistence.RecordWriteResult;
import com.zenner.datahub.stats.writer.persistence.RecordWriteStatus;
import com.zenner.datahub.stats.writer.persistence.StatsBatchWriter;
import java.util.List;
import java.util.Objects;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

/** Deserializes one lorastats delivery and persists it through the batch-shaped API. */
@Component
public final class LorastatsMessageProcessor implements StatsMessageProcessor {

    private final ObjectMapper objectMapper;
    private final StatsBatchWriter writer;

    public LorastatsMessageProcessor(ObjectMapper objectMapper, StatsBatchWriter writer) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @Override
    public void process(StatType expectedType, Message message) throws Exception {
        if (expectedType != LorastatsConsumerDescriptor.TYPE) {
            throw new IllegalArgumentException(
                    "expectedType must be " + LorastatsConsumerDescriptor.TYPE.wireName());
        }
        Objects.requireNonNull(message, "message");

        StatsEnvelope envelope = objectMapper.readValue(message.getBody(), StatsEnvelope.class);
        List<RecordWriteResult> results = writer.write(List.of(envelope));
        if (results.size() != 1 || results.getFirst().index() != 0) {
            throw new IllegalStateException("Single-record write returned an invalid result shape");
        }

        RecordWriteResult result = results.getFirst();
        if (result.status() == RecordWriteStatus.FAILED) {
            throw result.failureOptional().orElseThrow();
        }
        // INSERTED and DUPLICATE_SUPPRESSED are both committed, durable outcomes.
        // Returning lets LorastatsConsumer issue the manual broker ACK.
    }
}
