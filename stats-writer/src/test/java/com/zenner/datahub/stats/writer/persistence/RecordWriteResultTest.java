package com.zenner.datahub.stats.writer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RecordWriteResultTest {

    @Test
    void enforcesFailureOnlyForFailedOutcomes() {
        RuntimeException failure = new IllegalStateException("database unavailable");

        assertThat(RecordWriteResult.failed(7, failure).failureOptional())
                .containsSame(failure);
        assertThat(RecordWriteResult.inserted(0).failureOptional()).isEmpty();
        assertThat(RecordWriteResult.duplicateSuppressed(1).failureOptional()).isEmpty();
        assertThatThrownBy(
                        () ->
                                new RecordWriteResult(
                                        0, RecordWriteStatus.INSERTED, failure))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
