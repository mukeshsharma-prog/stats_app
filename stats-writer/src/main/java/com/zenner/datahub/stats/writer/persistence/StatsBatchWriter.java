package com.zenner.datahub.stats.writer.persistence;

import com.zenner.datahub.stats.common.model.StatsEnvelope;
import java.util.List;

/**
 * Batch-shaped persistence boundary required by G3/D11.
 *
 * <p>T-021 supplies an individual-transaction implementation behind this list API.
 * A future measured micro-batch implementation can replace it without changing
 * consumers, while the per-index results retain single-record DLQ precision.</p>
 */
@FunctionalInterface
public interface StatsBatchWriter {

    List<RecordWriteResult> write(List<StatsEnvelope> records);
}
