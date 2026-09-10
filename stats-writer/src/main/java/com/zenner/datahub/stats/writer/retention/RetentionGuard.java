package com.zenner.datahub.stats.writer.retention;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.common.model.StatsEnvelope;
import com.zenner.datahub.stats.common.model.Timestamps;
import com.zenner.datahub.stats.writer.validation.ValidatedRecord;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Late arrival: a record whose {@code ts.device} is already past the retention window
 * (T-026; STA-23B; ADR-003 option B, signed as D5b → DL-112).
 *
 * <h2>Why the record is skipped rather than stored</h2>
 *
 * <p>Retention keys on {@code ts.device} — measurement time, not arrival time — and Q15b
 * confirmed that really is what the device stamps (DL-106). So a gateway back from a long
 * outage delivers records that are already outside the window, and the daily partition
 * that would hold them no longer exists. Storing one is not an option the database offers:
 * the insert fails with {@code no partition of relation … found for row}. Of the three
 * options ADR-003 weighed, a {@code DEFAULT} catch-all partition degrades pruning and
 * grows unwatched, and clamping the timestamp to the oldest live partition falsifies the
 * time series. Skipping is the one that neither corrupts the data nor hides the loss.</p>
 *
 * <h2>Why it is never dead-lettered — this is the part that is easy to get wrong</h2>
 *
 * <p>The record is <em>valid</em>. Nothing about it is malformed, so the dead-letter queue
 * would be describing it wrongly, and worse: the DLQ carries no TTL and never expires
 * anything (ADR-002 §12), disposition is manual, and a redrive re-derives the identical
 * verdict — the record can only have got <em>older</em> since. It would sit there forever
 * being rejected on every attempt. That is also trap 18 read from the other end: a message
 * redriven from the DLQ after roughly ninety days lands in this class and increments this
 * counter. <strong>That is a no-op that looks like a recovery</strong>, and the runbook
 * (T-052) has to say so out loud.</p>
 *
 * <h2>Why this returns {@code false} where {@link
 * com.zenner.datahub.stats.writer.validation.PayloadGate} throws</h2>
 *
 * <p>The two gates differ in consequence, so they differ in signature. A payload failure
 * throws {@code PermanentFailureException}, which is the module's channel to the DLQ. This
 * one has to end in a broker ACK, and an exception is the one thing that cannot produce
 * one — so it returns a decision and the caller stops. The asymmetry is deliberate: making
 * them look alike is how the skip would eventually acquire a dead-letter it must not
 * have.</p>
 *
 * <p>Deliberate data loss by a signed decision is still data loss, so it is metered
 * ({@link RetentionMetrics#EXPIRED_ON_ARRIVAL}), logged, and alerted on when the counter
 * is non-zero — the addition engineering made to option B on 29 Aug 2026, because a
 * systemic gateway backlog and a harmless one-off are indistinguishable in a raw count.</p>
 */
@Component
public class RetentionGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetentionGuard.class);

    private final RetentionWindow window;
    private final RetentionMetrics metrics;

    public RetentionGuard(RetentionWindow window, RetentionMetrics metrics) {
        this.window = Objects.requireNonNull(window, "window");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Whether {@code record} still has a partition to land in.
     *
     * @return {@code true} to continue to the insert; {@code false} to skip the write and
     *     acknowledge the delivery. Never throws for a late record — an exception here
     *     would route it to the retry arm.
     */
    public boolean admit(StatType type, ValidatedRecord record) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(record, "record");

        StatsEnvelope envelope = record.envelope();
        Timestamps ts = Objects.requireNonNull(envelope.ts(), "record.ts");
        Instant tsDevice = Objects.requireNonNull(ts.device(), "record.ts.device");

        if (!window.isExpiredOnArrival(type, tsDevice)) {
            return true;
        }

        metrics.expiredOnArrival(type);
        // Envelope members only — uid is the correlation ID by decision (D1) and clsbox is
        // what makes a systemic backlog attributable to one gateway. No payload reaches
        // this line at any level (guard P1, DL-102).
        LOGGER.warn(
                "Record arrived past its retention window and was SKIPPED, not stored and "
                        + "not dead-lettered; the delivery is acknowledged: uid={}, type={}, "
                        + "clsbox={}, tsDevice={}, tsServer={}, cutoff={}, ageDays={}, "
                        + "retentionDays={}",
                envelope.uid(),
                type.wireName(),
                envelope.clsbox(),
                tsDevice,
                ts.server(),
                window.cutoff(type),
                ChronoUnit.DAYS.between(
                        tsDevice.atZone(ZoneOffset.UTC).toLocalDate(), window.today()),
                window.retentionDaysFor(type));
        return false;
    }
}
