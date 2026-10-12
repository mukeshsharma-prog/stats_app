package com.zenner.datahub.stats.writer.consumer;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.writer.log.WriterLogContext;
import com.zenner.datahub.stats.writer.persistence.RecordWriteResult;
import com.zenner.datahub.stats.writer.persistence.RecordWriteStatus;
import com.zenner.datahub.stats.writer.persistence.StatsBatchWriter;
import com.zenner.datahub.stats.writer.retention.RetentionGuard;
import com.zenner.datahub.stats.writer.validation.PayloadGate;
import com.zenner.datahub.stats.writer.validation.ValidatedRecord;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

@Component
@Conditional(NetstatsDeploymentCondition.class)
public final class NetstatsMessageProcessor implements StatsMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(NetstatsMessageProcessor.class);

    private final PayloadGate gate;
    private final RetentionGuard retentionGuard;
    private final StatsBatchWriter writer;

    public NetstatsMessageProcessor(
            PayloadGate gate,
            RetentionGuard retentionGuard,
            @Qualifier(NetstatsConsumerDescriptor.BATCH_WRITER_BEAN) StatsBatchWriter writer) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.retentionGuard = Objects.requireNonNull(retentionGuard, "retentionGuard");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @Override
    public StatType statType() {
        return NetstatsConsumerDescriptor.TYPE;
    }

    @Override
    public void process(StatType expectedType, Message message) throws Exception {
        if (expectedType != NetstatsConsumerDescriptor.TYPE) {
            throw new IllegalArgumentException(
                    "expectedType must be " + NetstatsConsumerDescriptor.TYPE.wireName());
        }
        Objects.requireNonNull(message, "message");

        ValidatedRecord validated = gate.admit(NetstatsConsumerDescriptor.TYPE, message);
        WriterLogContext.bindRecord(validated.envelope());
        log.atDebug().addKeyValue("event", "consumed").log("Record admitted by the payload gate");

        if (!retentionGuard.admit(NetstatsConsumerDescriptor.TYPE, validated)) {
            // Skipped, metered and logged by the guard; ACKed by returning (D5b, DL-133).
            return;
        }

        List<RecordWriteResult> results = writer.write(List.of(validated));
        if (results.size() != 1 || results.getFirst().index() != 0) {
            throw new IllegalStateException("Single-record write returned an invalid result shape");
        }

        RecordWriteResult result = results.getFirst();
        if (result.status() == RecordWriteStatus.FAILED) {
            throw result.failureOptional().orElseThrow();
        }
        String event = result.status() == RecordWriteStatus.INSERTED
                ? "persisted"
                : "duplicate_suppressed";
        log.atDebug()
                .addKeyValue("event", event)
                .log("Record reached a committed, durable outcome");
    }
}
