package com.zenner.datahub.stats.writer.retry;

/**
 * The retry or dead-letter copy was not confirmed, so the original must not be ACKed.
 *
 * <p>It exists to make the ordering hazard impossible to get wrong by accident. The
 * tempting sequence — ACK the failed delivery, then publish it onward — opens a window
 * in which the original is gone and the copy does not exist, and a crash inside that
 * window loses the record <em>silently</em>: the broker shows nothing, the database
 * shows nothing, and Heimdal was told 200 long ago. Throwing here leaves the delivery
 * unacknowledged, so the broker keeps the only copy and redelivers it.</p>
 */
public class UnconfirmedHandoffException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnconfirmedHandoffException(String message) {
        super(message);
    }
}
