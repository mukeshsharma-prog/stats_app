package com.zenner.datahub.stats.writer.consumer;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.writer.persistence.RecordWriteResult;
import com.zenner.datahub.stats.writer.persistence.RecordWriteStatus;
import com.zenner.datahub.stats.writer.persistence.StatsBatchWriter;
import com.zenner.datahub.stats.writer.validation.PayloadGate;
import com.zenner.datahub.stats.writer.validation.ValidatedRecord;
import java.util.List;
import java.util.Objects;
import org.springframework.amqp.core.Message;
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
 */
@Component
@Conditional(LorastatsDeploymentCondition.class)
public final class LorastatsMessageProcessor implements StatsMessageProcessor {

    private final PayloadGate gate;
    private final StatsBatchWriter writer;

    public LorastatsMessageProcessor(PayloadGate gate, StatsBatchWriter writer) {
        this.gate = Objects.requireNonNull(gate, "gate");
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
        List<RecordWriteResult> results = writer.write(List.of(validated));
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
