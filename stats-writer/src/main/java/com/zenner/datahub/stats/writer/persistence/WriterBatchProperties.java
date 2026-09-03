package com.zenner.datahub.stats.writer.persistence;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The G3/D11 write-boundary settings.
 *
 * <p>Micro-batching is deliberately disabled in T-021. The interface is already
 * batch-shaped, but the implementation commits one record at a time so one poison
 * record can be isolated for the later T-024 DLQ path.</p>
 */
@ConfigurationProperties(prefix = "stats.writer.batch")
public record WriterBatchProperties(boolean enabled, int maxRecords, Duration maxWait) {

    public WriterBatchProperties {
        if (enabled) {
            throw new IllegalArgumentException(
                    "stats.writer.batch.enabled must remain false until micro-batching is implemented");
        }
        if (maxRecords < 1) {
            throw new IllegalArgumentException("maxRecords must be at least 1");
        }
        Objects.requireNonNull(maxWait, "maxWait");
        if (maxWait.isZero() || maxWait.isNegative()) {
            throw new IllegalArgumentException("maxWait must be greater than zero");
        }
    }
}
