package com.zenner.datahub.stats.input.publish;

/**
 * The result of handing one record to the broker.
 *
 * <p>There are only two outcomes that matter to the caller:
 * {@link #CONFIRMED} is the <em>only</em> state that may become a 2xx.
 * Everything else is a 503, because Heimdal owns retry and will resend on any
 * non-2xx — but only if we tell it we failed.</p>
 */
public enum PublishOutcome {

    /** The broker confirmed the publish. Only now may the caller be told success. */
    CONFIRMED,

    /**
     * The broker was unreachable, the confirm did not arrive in time, or the message
     * was returned as unroutable. All three mean the same thing to Heimdal: not
     * accepted, send it again.
     */
    NOT_CONFIRMED
}
