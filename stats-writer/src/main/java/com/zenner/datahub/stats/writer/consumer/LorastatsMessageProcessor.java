package com.zenner.datahub.stats.writer.consumer;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.writer.persistence.RecordWriteResult;
import com.zenner.datahub.stats.writer.persistence.RecordWriteStatus;
import com.zenner.datahub.stats.writer.persistence.StatsBatchWriter;
import com.zenner.datahub.stats.writer.log.WriterLogContext;
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
 * Validates and persists one lorastats delivery through the batch-shaped API.
 *
 * <p>The gate runs <strong>before</strong> the write, and that ordering is the point: a
 * record that fails its payload schema must never open a transaction, and it must reach
 * the dead-letter queue without spending 35½ minutes in three retry tiers first
 * (T-057, ADR-007 §5). {@link PayloadGate} is type-agnostic, so the sysstats processor
 * that follows in Phase 3 is a sibling of this class rather than a copy of its gate —
 * which is what rule 6 asks for and what stops the fifth copy being the one that omits
 * the check.</p>
 *
 * <p>{@link RetentionGuard} then runs between the gate and the write, in that order and
 * not the other way round (T-026, FLOW.md §4.1 step 3). A record too old to store is still
 * worth validating: if it is <em>also</em> malformed it dead-letters, where a schema
 * mismatch can be diagnosed, rather than being discarded as merely late. The reverse order
 * would silently swallow the more informative of the two failures.</p>
 */
@Component
@Conditional(LorastatsDeploymentCondition.class)
public final class LorastatsMessageProcessor implements StatsMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(LorastatsMessageProcessor.class);

    private final PayloadGate gate;
    private final RetentionGuard retentionGuard;
    private final StatsBatchWriter writer;

    /**
     * The writer is resolved by <em>this type's</em> bean name, not by "the only
     * {@link StatsBatchWriter}". With a second type in the context that injection is
     * ambiguous and the context fails — adding sysstats would have broken lorastats,
     * which is the exact failure rule 6 forbids and {@link StatsMessageProcessorAvailability}
     * already guards against for processors (T-035, DL-148).
     */
    public LorastatsMessageProcessor(
            PayloadGate gate,
            RetentionGuard retentionGuard,
            @Qualifier(LorastatsConsumerDescriptor.BATCH_WRITER_BEAN) StatsBatchWriter writer) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.retentionGuard = Objects.requireNonNull(retentionGuard, "retentionGuard");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @Override
    public StatType statType() {
        return LorastatsConsumerDescriptor.TYPE;
    }

    @Override
    public void process(StatType expectedType, Message message) throws Exception {
        if (expectedType != LorastatsConsumerDescriptor.TYPE) {
            throw new IllegalArgumentException(
                    "expectedType must be " + LorastatsConsumerDescriptor.TYPE.wireName());
        }
        Objects.requireNonNull(message, "message");

        ValidatedRecord validated = gate.admit(LorastatsConsumerDescriptor.TYPE, message);
        // Bound only now: the gate has validated the envelope, so uid, tenant and clsbox
        // are the schema's values rather than whatever an unvalidated body carried (T-005).
        WriterLogContext.bindRecord(validated.envelope());
        log.atDebug().addKeyValue("event", "consumed").log("Record admitted by the payload gate");

        if (!retentionGuard.admit(LorastatsConsumerDescriptor.TYPE, validated)) {
            // Past its retention window: skipped, metered and logged by the guard, and
            // ACKed by returning normally. Throwing would route a perfectly valid record
            // into the retry arm and then a DLQ that never expires, where every redrive
            // would reach this same line again (D5b, DL-112).
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
        // INSERTED and DUPLICATE_SUPPRESSED are both committed, durable outcomes.
        // Returning lets LorastatsConsumer issue the manual broker ACK.
    }
}
