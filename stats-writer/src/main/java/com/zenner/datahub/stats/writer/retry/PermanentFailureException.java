package com.zenner.datahub.stats.writer.retry;

/**
 * A failure that will fail identically on every future attempt.
 *
 * <p>Throwing this from a processor sends the delivery <strong>straight to the type's
 * DLQ</strong>, skipping all three retry tiers. That is a deliberate saving of 35½
 * minutes of queue time per record: a payload that does not match its schema, a
 * counter that overflows {@code BIGINT}, a violated table invariant — none of them
 * become true by waiting. At 115.2m records/day a systematic permanent failure
 * retried three times is three times the broker traffic and three times the delay
 * before the DLQ depth alert fires.</p>
 *
 * <p>The default classification is the other way round: anything not recognised as
 * permanent is <em>retried</em>. Guessing "permanent" for an unfamiliar exception
 * dead-letters a record that a 30-second wait would have written.</p>
 */
public class PermanentFailureException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PermanentFailureException(String message, Throwable cause) {
        super(message, cause);
    }

    public PermanentFailureException(String message) {
        super(message);
    }
}
