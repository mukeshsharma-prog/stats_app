package com.zenner.datahub.stats.writer.persistence;

import com.zenner.datahub.stats.writer.validation.ValidatedRecord;
import java.util.List;

/**
 * Batch-shaped persistence boundary required by G3/D11.
 *
 * <p>T-021 supplies an individual-transaction implementation behind this list API.
 * A future measured micro-batch implementation can replace it without changing
 * consumers, while the per-index results retain single-record DLQ precision.</p>
 *
 * <p>The input is {@link ValidatedRecord}, not a bare envelope, since T-057. That is a
 * signature rather than a convention: a writer that accepted an unvalidated envelope
 * could be called with one, and the payload gate would hold only for as long as every
 * caller remembered it. It also carries the schema label that admitted the record
 * through to the {@code schema_version} column, which ADR-003 D7 wants to mean "the
 * contract that admitted this row" rather than "the contract its author had in mind".</p>
 */
@FunctionalInterface
public interface StatsBatchWriter {

    List<RecordWriteResult> write(List<ValidatedRecord> records);
}
