package com.zenner.datahub.stats.api.query;

/**
 * Masks a subscriber identifier on the way out of the API.
 *
 * <p><strong>Masked at read, raw at rest — by decision.</strong> The approver chose the
 * API over the Writer: the table, the dead-letter queue and backups keep the IMSI exactly
 * as Heimdal sent it, so ops can still correlate a SIM with the carrier, and the decision
 * can be tightened later without a migration or a DLQ purge, which is never allowed. The
 * consequence is that this class is the <em>only</em> control between the stored value
 * and a tenant's token: every read path goes through {@link LtestatsReader}, and nothing
 * else in {@code stats-api} may select the column.</p>
 *
 * <p><strong>What stays visible.</strong> The network prefix — the record's own
 * {@code plmn} (MCC + MNC) when the IMSI starts with it, otherwise the first five digits —
 * and, when at least eight subscriber digits follow it, the last four. Everything between
 * is {@code *}, one per digit, so the length is preserved. The prefix is already returned
 * as {@code plmn}; the last four let an operator tell the SIMs of their own fleet apart
 * without the subscriber number. A value too short to leave anything hidden after the
 * prefix is masked completely rather than returned in clear.</p>
 */
final class ImsiMask {

    static final char MASK = '*';

    /** Subscriber digits left visible at the end, when there are enough to hide the rest. */
    static final int VISIBLE_TAIL = 4;

    /** Subscriber digits that must remain hidden before any tail is shown. */
    static final int MIN_HIDDEN = 4;

    /** Prefix kept when the record's plmn does not prefix the IMSI. */
    static final int DEFAULT_PREFIX = 5;

    private ImsiMask() {}

    static String mask(String imsi, String plmn) {
        if (imsi == null) {
            return null;
        }
        int length = imsi.length();
        int prefix = plmn != null && !plmn.isEmpty() && imsi.startsWith(plmn)
                ? plmn.length()
                : DEFAULT_PREFIX;
        int subscriber = length - prefix;
        if (subscriber < MIN_HIDDEN) {
            return String.valueOf(MASK).repeat(length);
        }
        int tail = subscriber >= MIN_HIDDEN + VISIBLE_TAIL ? VISIBLE_TAIL : 0;
        return imsi.substring(0, prefix)
                + String.valueOf(MASK).repeat(subscriber - tail)
                + imsi.substring(length - tail);
    }
}
