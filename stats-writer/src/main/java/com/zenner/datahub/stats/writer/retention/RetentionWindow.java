package com.zenner.datahub.stats.writer.retention;

import com.zenner.datahub.stats.common.model.StatType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The retention cutoff — one definition, used by both halves of retention
 * (STA-23A, STA-23B; ADR-003 D1 and its late-arrival consequence).
 *
 * <h2>Why this is one class and not two calculations</h2>
 *
 * <p>Two things must agree on the same instant, and the failure when they do not is
 * silent in one direction and permanent in the other:</p>
 *
 * <ul>
 *   <li>{@link PartitionRetention} drops a partition once it is <em>wholly</em> older than
 *       the cutoff.</li>
 *   <li>{@link RetentionGuard} skips a record whose {@code ts.device} is older than the
 *       cutoff, because no partition can hold it (T-026, DL-112).</li>
 *   <li>{@link PartitionMaintenance} ensures a partition for every day from the cutoff
 *       through the pre-creation horizon (DL-149), so the converse holds too: a record the
 *       guard admits always has somewhere to go.</li>
 * </ul>
 *
 * <p>If the guard were more permissive by even a day, it would admit a record into a
 * partition the sweep had just dropped: the insert fails, the record dead-letters, and a
 * redrive re-derives the identical verdict forever in a queue ADR-002 §12 forbids ever
 * expiring. If the sweep were more permissive, partitions past the window would survive
 * with nothing to say so. Deriving both from this class makes the disagreement
 * unrepresentable rather than merely unlikely.</p>
 *
 * <h2>The arithmetic, and which way it leans</h2>
 *
 * <pre>
 *   cutoffDay = today(UTC) − retentionDays
 *   a record  is expired  ⇔  ts.device &lt; cutoffDay 00:00Z
 *   a partition is droppable ⇔ its day &lt; cutoffDay   (i.e. it ends at or before the cutoff)
 * </pre>
 *
 * <p>That keeps {@code retentionDays + 1} daily partitions — today plus the window — and
 * it leans that way on purpose. Over-retaining by one day costs one partition; under-
 * retaining by one day deletes records that are still inside the window the business
 * asked for, and nothing would report it.</p>
 *
 * <h2>The clock is UTC and the basis is {@code ts.device}</h2>
 *
 * <p>{@code ts.device} is when the <em>gateway measured</em> the record, not when we
 * received it (STA-23B), and Q15b confirmed it is genuinely device-assigned (DL-106). A
 * gateway back from a three-week outage therefore delivers records already 21 days into
 * their window. That is the answered business rule, not a defect — do not "fix" it to
 * {@code ts.server}.</p>
 */
@Component
public class RetentionWindow {

    private final RetentionProperties properties;
    private final Clock clock;

    public RetentionWindow(RetentionProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The first UTC day still retained for {@code type}. */
    public LocalDate cutoffDay(StatType type) {
        Objects.requireNonNull(type, "type");
        return today().minusDays(properties.retentionDaysFor(type));
    }

    /**
     * The instant at which {@code type}'s window opens: records at or after it are kept,
     * records before it have no live partition.
     */
    public Instant cutoff(StatType type) {
        return cutoffDay(type).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * Whether a record measured at {@code tsDevice} arrived after its own partition was
     * already eligible for retention.
     *
     * <p>Strictly before the cutoff, so a record on the cutoff day itself is kept — the
     * boundary belongs to the retained side, matching {@link #isDroppable}.</p>
     */
    public boolean isExpiredOnArrival(StatType type, Instant tsDevice) {
        Objects.requireNonNull(tsDevice, "tsDevice");
        return tsDevice.isBefore(cutoff(type));
    }

    /**
     * Whether the daily partition covering {@code day} is entirely outside the window.
     *
     * <p>The partition covers {@code [day, day+1)}, so it is droppable exactly when it
     * ends at or before the cutoff — which is {@code day < cutoffDay}. Today's partition
     * and every future one can never satisfy it while {@code retentionDays >= 1}, so the
     * sweep cannot fight pre-creation over the same day.</p>
     */
    public boolean isDroppable(StatType type, LocalDate day) {
        Objects.requireNonNull(day, "day");
        return day.isBefore(cutoffDay(type));
    }

    /** Today, in UTC. Partition bounds are UTC, so the comparison has to be. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    /** How many whole days of history {@code type} keeps, for logs and metrics. */
    public int retentionDaysFor(StatType type) {
        return properties.retentionDaysFor(type);
    }
}
