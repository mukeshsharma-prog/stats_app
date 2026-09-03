package com.zenner.datahub.stats.writer.retention;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.writer.retention.PartitionPrecreator.PartitionPrecreationResult;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves which stat types this deployment maintains and ensures their partitions.
 *
 * <p>Requirement STA-23C; ADR-003 D1 (pre-creation is a scheduled task in this
 * module, {@code pg_partman} is never a dependency) and D3 (one advisory lock per
 * stat type, because D8 runs N replicas per type).</p>
 *
 * <p><strong>Two callers, two failure policies, on purpose.</strong>
 * {@link #ensureBeforeIngestion()} runs at startup and lets the failure escape, so
 * the module refuses to start rather than consume into a table with no partition.
 * {@link #ensureOnSchedule()} runs on the cron in an already-serving process, where
 * killing four healthy stat types because a fifth failed would be the worse
 * outcome, so it isolates each type and logs at ERROR. Both are loud; only one is
 * fatal. ADR-003 D1 requires the loudness — "a silent failure here stops ingestion
 * for a whole stat type".</p>
 */
@Component
public class PartitionMaintenance {

    static final String ALL_TYPES = "all";

    private static final Logger LOGGER = LoggerFactory.getLogger(PartitionMaintenance.class);

    private final PartitionPrecreator precreator;
    private final RetentionProperties properties;

    /** The single type this deployment serves, or {@code null} for development {@code all} mode. */
    private final StatType selectedType;

    public PartitionMaintenance(
            PartitionPrecreator precreator,
            RetentionProperties properties,
            @Value("${stats.writer.stat-type:" + ALL_TYPES + "}") String configuredStatType) {
        this.precreator = Objects.requireNonNull(precreator, "precreator");
        this.properties = Objects.requireNonNull(properties, "properties");
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
            ensureOne(type, "startup");
        }
    }

    /** Ensures partitions on the retention cron, isolating each stat type from the others. */
    public void ensureOnSchedule() {
        for (StatType type : targetTypes()) {
            try {
                ensureOne(type, "schedule");
            } catch (RuntimeException failure) {
                // Never rethrow here: this loop is the only thing standing between the
                // other stat types and no partitions tomorrow.
                LOGGER.error(
                        "Scheduled partition pre-creation FAILED; this stat type will stop "
                                + "ingesting once the last pre-created day passes: statType={}",
                        type.wireName(),
                        failure);
            }
        }
    }

    private void ensureOne(StatType type, String trigger) {
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        PartitionPrecreationResult result =
                precreator.ensurePartitions(type, todayUtc, properties.preCreateDays());
        if (!result.lockAcquired()) {
            // ADR-003 D3: another replica owns this type's maintenance run. Not an error.
            LOGGER.debug(
                    "Skipping partition pre-creation because the advisory lock is held: "
                            + "statType={}, trigger={}",
                    type.wireName(),
                    trigger);
            return;
        }
        LOGGER.info(
                "Ensured daily partitions: statType={}, trigger={}, firstDay={}, "
                        + "preCreateDays={}, partitionCount={}",
                type.wireName(),
                trigger,
                todayUtc,
                properties.preCreateDays(),
                result.partitionsEnsured());
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
