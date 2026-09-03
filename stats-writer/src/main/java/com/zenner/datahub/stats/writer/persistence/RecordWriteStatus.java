package com.zenner.datahub.stats.writer.persistence;

/** Durable outcome of writing one element from a batch-shaped request. */
public enum RecordWriteStatus {
    INSERTED,
    DUPLICATE_SUPPRESSED,
    FAILED
}
