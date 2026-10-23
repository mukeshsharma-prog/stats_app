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

/**
 * Validates and persists one omsstats delivery through the batch-shaped API.
 *
 * <p>{@code ts.converter} is on every production omsstats record and is
 * accepted, preserved in {@code passthrough} and never read: nothing here — or anywhere
 * on this path — normalises a timestamp. Accepting a field is not acting on it.</p>
 *
 * <p>Gate, then guard, then write — the order the lorastats sibling settled and the
 * reasons it gives: a payload failure must never open a transaction and
 * goes straight to the DLQ; a late record is validated first so that one which is
 * <em>also</em> malformed dead-letters where it can be diagnosed rather than being
 * discarded as merely late. {@link PayloadGate} and {@link RetentionGuard} are shared and
 * type-agnostic, which is what keeps this class a sibling rather than a copy of a gate.</p>
 */
@Component
@Conditional(OmsstatsDeploymentCondition.class)
public final class OmsstatsMessageProcessor implements StatsMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(OmsstatsMessageProcessor.class);

    private final PayloadGate gate;
    private final RetentionGuard retentionGuard;
    private final StatsBatchWriter writer;

    public OmsstatsMessageProcessor(
            PayloadGate gate,
            RetentionGuard retentionGuard,
            @Qualifier(OmsstatsConsumerDescriptor.BATCH_WRITER_BEAN) StatsBatchWriter writer) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.retentionGuard = Objects.requireNonNull(retentionGuard, "retentionGuard");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @Override
    public StatType statType() {
        return OmsstatsConsumerDescriptor.TYPE;
    }

    @Override
    public void process(StatType expectedType, Message message) throws Exception {
        if (expectedType != OmsstatsConsumerDescriptor.TYPE) {
            throw new IllegalArgumentException(
                    "expectedType must be " + OmsstatsConsumerDescriptor.TYPE.wireName());
        }
        Objects.requireNonNull(message, "message");

        ValidatedRecord validated = gate.admit(OmsstatsConsumerDescriptor.TYPE, message);
        WriterLogContext.bindRecord(validated.envelope());
        log.atDebug().addKeyValue("event", "consumed").log("Record admitted by the payload gate");

        if (!retentionGuard.admit(OmsstatsConsumerDescriptor.TYPE, validated)) {
            // Skipped, metered and logged by the guard; ACKed by returning.
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
