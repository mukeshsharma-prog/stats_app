package com.zenner.datahub.stats.writer.retention;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.writer.log.SafeFailure;
import com.zenner.datahub.stats.writer.retention.PartitionPrecreator.PartitionPrecreationResult;
import com.zenner.datahub.stats.writer.retention.PartitionRetention.RetentionResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves which stat types this deployment maintains, and runs one maintenance pass over
 * them: pre-create, then retain.
 *
 * <p>Requirement STA-23A/STA-23C; ADR-003 D1 (pre-creation is a scheduled task in this
 * module, {@code pg_partman} is never a dependency), D3 (one advisory lock per stat type,
 * because D8 runs N replicas per type, and stagger the types rather than firing them
 * together) and D4 (retention runs once daily, off-peak).</p>
 *
 * <p><strong>Pre-creation runs before retention, in that order, in one pass.</strong> They
 * are two halves of one job — the same lock, the same schedule, the same partition set —
 * and ordering them explicitly is what stops a badly timed run from dropping yesterday
 * before tomorrow exists. Two separately scheduled tasks on one cron would have left the
 * order to whatever the scheduler happened to register first.</p>
 *
 * <p><strong>Pre-creation covers the whole retention window, not just the days ahead
 * (DL-149).</strong> The range ensured on every pass is {@code [cutoffDay, today +
 * pre-create-days]}, both ends from {@link RetentionWindow} — the same cutoff the sweep
 * drops on and the guard admits on. Ahead-only pre-creation left every retained day
 * before first deployment, and every day a scheduled run missed, without a partition;
 * a valid record for such a day — a gateway back from three weeks offline, exactly the
 * path STA-23B says is normal — failed its INSERT with SQLSTATE 23514 and was
 * dead-lettered as <em>permanent</em>, where a redrive re-derives the same verdict
 * forever. Empty partitions are catalogue entries; a lost record is a lost record.</p>
 *
 * <p><strong>Two callers, two failure policies, on purpose.</strong>
 * {@link #ensureBeforeIngestion()} runs at startup and lets the failure escape, so
 * the module refuses to start rather than consume into a table with no partition.
 * {@link #maintainOnSchedule()} runs on the cron in an already-serving process, where
 * killing four healthy stat types because a fifth failed would be the worse
 * outcome, so it isolates each type and logs at ERROR. Both are loud; only one is
 * fatal. ADR-003 D1 requires the loudness — "a silent failure here stops ingestion
 * for a whole stat type".</p>
 *
 * <p>Retention is isolated from pre-creation within a type as well. They fail for
 * unrelated reasons — pre-creation needs DDL permission and a correct horizon, retention
 * needs a detach that is not wedged — and a type that cannot reclaim yesterday must still
 * get tomorrow's partition, or a storage problem becomes an ingestion outage.</p>
 */
@Component
public class PartitionMaintenance {

    static final String ALL_TYPES = "all";

    private static final Logger LOGGER = LoggerFactory.getLogger(PartitionMaintenance.class);

    private final PartitionPrecreator precreator;
    private final PartitionRetention retention;
    private final RetentionProperties properties;
    private final RetentionWindow window;
    private final RetentionMetrics metrics;
    private final Clock clock;

    /** The single type this deployment serves, or {@code null} for development {@code all} mode. */
    private final StatType selectedType;

    public PartitionMaintenance(
            PartitionPrecreator precreator,
            PartitionRetention retention,
            RetentionProperties properties,
            RetentionWindow window,
            RetentionMetrics metrics,
            Clock clock,
            @Value("${stats.writer.stat-type:" + ALL_TYPES + "}") String configuredStatType) {
        this.precreator = Objects.requireNonNull(precreator, "precreator");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.window = Objects.requireNonNull(window, "window");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.selectedType = parseSelectedType(configuredStatType);
    }

    /**
     * Ensures partitions before this module consumes anything, failing startup if it
     * cannot.
     *
     * <p>An insert with no matching partition fails with {@code no partition of
     * relation ... found for row}. Under the T-020 manual-ACK contract that failure
     * leaves the delivery unacknowledged, so a writer started into an unpartitioned
     * table does not lose data — it wedges, silently, holding its whole prefetch
     * window until someone restarts it. Refusing to start is the louder and cheaper
     * failure, and it keeps the broker holding the records until the cause is fixed.</p>
     *
     * <p>This is also where the retention counters are registered, so they export at zero
     * from startup. {@code records_expired_on_arrival} answers "is anything arriving too
     * late to keep", and that question has to be answerable as <em>zero</em> rather than as
     * a missing series. A deployment that sets {@code pre-create-on-startup=false} because
     * a separate job owns partition DDL registers them on its first scheduled pass
     * instead.</p>
     */
    public void ensureBeforeIngestion() {
        if (selectedType != null && !precreator.parentTableExists(selectedType)) {
            // A deployment configured for a type whose migration has not landed cannot
            // work at all. Say so here, where the message can name the cause, rather
            // than letting it surface as a confusing DDL error inside pre-creation.
            throw new IllegalStateException(
                    "stats.writer.stat-type is "
                            + selectedType.wireName()
                            + " but no partitioned table of that name exists; the migration "
                            + "for this stat type has not been applied");
        }
        for (StatType type : targetTypes()) {
            metrics.registerFor(type);
            ensureOne(type, "startup");
        }
    }

    /**
     * One scheduled maintenance pass, isolating each stat type from the others.
     *
     * <p>Types are taken in turn at their own stagger offset (ADR-003 D3). In the
     * production shape — one deployment per type, all five firing on the same cron — the
     * offset is what separates them; in development {@code all} mode it separates the five
     * passes inside one process. One mechanism covers both because the offset is derived
     * from the type, not from the position in this loop.</p>
     */
    public void maintainOnSchedule() {
        Instant runStart = clock.instant();
        for (StatType type : targetTypes()) {
            if (!awaitStaggerSlot(runStart, type)) {
                return;
            }
            metrics.registerFor(type);
            try {
                ensureOne(type, "schedule");
            } catch (RuntimeException failure) {
                // Never rethrow here: this loop is the only thing standing between the
                // other stat types and no partitions tomorrow.
                LOGGER.atError()
                        .addKeyValue("event", "partition_precreation_failed")
                        .addKeyValue("statType", type.wireName())
                        .addKeyValue("errorCode", "partition_precreation_failed")
                        .addKeyValue("cause", SafeFailure.describe(failure))
                        .log(
                                "Scheduled partition pre-creation failed; this stat type will "
                                        + "stop ingesting once the last pre-created day passes");
            }
            try {
                retainOne(type);
            } catch (RuntimeException failure) {
                // Isolated from pre-creation above, and from the other types. Retention
                // failing is a storage problem; it must not become an ingestion one.
                LOGGER.atError()
                        .addKeyValue("event", "retention_failed")
                        .addKeyValue("statType", type.wireName())
                        .addKeyValue("errorCode", "retention_failed")
                        .addKeyValue("cause", SafeFailure.describe(failure))
                        .log(
                                "Scheduled retention FAILED; partitions past the window are still "
                                        + "attached and holding storage");
            }
        }
    }

    private void ensureOne(StatType type, String trigger) {
        // From the first retained day through the pre-creation horizon, inclusive. The
        // cutoff is RetentionWindow's, so the ensured set and the guard's admitted set
        // cannot disagree (DL-133, DL-149); the sweep that follows drops strictly before
        // the same day, so the two halves of the pass never fight over one partition.
        LocalDate firstDay = window.cutoffDay(type);
        int daysAfterFirst = window.retentionDaysFor(type) + properties.preCreateDays();
        PartitionPrecreationResult result =
                precreator.ensurePartitions(type, firstDay, daysAfterFirst);
        if (!result.lockAcquired()) {
            // ADR-003 D3: another replica owns this type's maintenance run. Not an error.
            LOGGER.atDebug()
                    .addKeyValue("event", "partition_precreation_skipped")
                    .addKeyValue("statType", type.wireName())
                    .addKeyValue("trigger", trigger)
                    .log("Skipping partition pre-creation because the advisory lock is held");
            return;
        }
        LOGGER.atInfo()
                .addKeyValue("event", "partitions_ensured")
                .addKeyValue("statType", type.wireName())
                .addKeyValue("trigger", trigger)
                .addKeyValue("firstDay", firstDay)
                .addKeyValue("lastDay", firstDay.plusDays(daysAfterFirst))
                .addKeyValue("retentionDays", window.retentionDaysFor(type))
                .addKeyValue("preCreateDays", properties.preCreateDays())
                .addKeyValue("partitionCount", result.partitionsEnsured())
                .log("Ensured daily partitions across the retention window and horizon");
    }

    private void retainOne(StatType type) {
        RetentionResult result = retention.sweep(type);
        if (!result.lockAcquired()) {
            LOGGER.atDebug()
                    .addKeyValue("event", "retention_skipped")
                    .addKeyValue("statType", type.wireName())
                    .log("Skipping retention because the advisory lock is held");
            return;
        }
        if (!result.changedAnything()) {
            // The steady state: nothing has aged out since yesterday's run. DEBUG, so a
            // daily no-op does not read as an event.
            LOGGER.atDebug()
                    .addKeyValue("event", "retention_no_op")
                    .addKeyValue("statType", type.wireName())
                    .addKeyValue("cutoffDay", result.cutoffDay())
                    .log("Retention found nothing past the window");
            return;
        }
        LOGGER.atInfo()
                .addKeyValue("event", "retention_applied")
                .addKeyValue("statType", type.wireName())
                .addKeyValue("cutoffDay", result.cutoffDay())
                .addKeyValue("retentionDays", properties.retentionDaysFor(type))
                .addKeyValue("partitionsDropped", result.partitionsDropped())
                .addKeyValue("orphansDropped", result.orphansDropped())
                .addKeyValue("detachesFinalised", result.detachesFinalised())
                .log("Applied retention");
    }

    /**
     * Waits until this type's slot in the run, so the five do not fire together.
     *
     * <p>Returns {@code false} if the wait was interrupted, which means the container is
     * shutting down: the run abandons the remaining types rather than racing a shutdown to
     * issue DDL. Nothing is lost by stopping — the next run recomputes everything from the
     * catalogue.</p>
     */
    private boolean awaitStaggerSlot(Instant runStart, StatType type) {
        Duration offset = properties.staggerFor(type);
        if (offset.isZero()) {
            return true;
        }
        Duration remaining = Duration.between(clock.instant(), runStart.plus(offset));
        if (remaining.isZero() || remaining.isNegative()) {
            return true;
        }
        try {
            Thread.sleep(remaining.toMillis());
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOGGER.atWarn()
                    .addKeyValue("event", "maintenance_interrupted")
                    .addKeyValue("statType", type.wireName())
                    .log(
                            "Maintenance was interrupted while waiting for a stat type's stagger "
                                    + "slot; the remaining types are left to the next run");
            return false;
        }
    }

    /**
     * The types this deployment maintains.
     *
     * <p>ADR-003 D8 deploys one instance per stat type in production, so this is
     * normally exactly one. In development {@code all} mode it is discovered from the
     * catalogue, which lets a single local container grow as each type's migration
     * lands without this class enumerating them.</p>
     */
    List<StatType> targetTypes() {
        if (selectedType != null) {
            return List.of(selectedType);
        }
        return precreator.existingParentTypes();
    }

    private static StatType parseSelectedType(String configuredStatType) {
        if (configuredStatType == null || configuredStatType.isBlank()) {
            throw new IllegalArgumentException("stats.writer.stat-type must not be blank");
        }
        String wireName = configuredStatType.trim();
        if (ALL_TYPES.equals(wireName)) {
            return null;
        }
        return StatType.fromWireName(wireName);
    }
}
