package com.zenner.datahub.stats.writer.persistence;

import java.util.Objects;
import java.util.Optional;

/**
 * Per-index write result preserving poison-record precision within a list.
 *
 * <p>The result deliberately identifies a record by list index instead of copying
 * identifiers or payload values into an error object that may later be logged.</p>
 */
public record RecordWriteResult(
        int index, RecordWriteStatus status, RuntimeException failure) {

    public RecordWriteResult {
        if (index < 0) {
            throw new IllegalArgumentException("index must be zero or greater");
        }
        Objects.requireNonNull(status, "status");
        if ((status == RecordWriteStatus.FAILED) != (failure != null)) {
            throw new IllegalArgumentException(
                    "failure must be present exactly when status is FAILED");
        }
    }

    public static RecordWriteResult inserted(int index) {
        return new RecordWriteResult(index, RecordWriteStatus.INSERTED, null);
    }

    public static RecordWriteResult duplicateSuppressed(int index) {
        return new RecordWriteResult(index, RecordWriteStatus.DUPLICATE_SUPPRESSED, null);
    }

    public static RecordWriteResult failed(int index, RuntimeException failure) {
        return new RecordWriteResult(
                index, RecordWriteStatus.FAILED, Objects.requireNonNull(failure, "failure"));
    }

    public Optional<RuntimeException> failureOptional() {
        return Optional.ofNullable(failure);
    }
}
