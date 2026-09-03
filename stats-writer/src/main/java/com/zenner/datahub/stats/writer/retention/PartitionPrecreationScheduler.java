package com.zenner.datahub.stats.writer.retention;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.writer.retention.PartitionPrecreator.PartitionPrecreationResult;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduled partition pre-creation owned by stats-writer. */
@Component
public class PartitionPrecreationScheduler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PartitionPrecreationScheduler.class);

    private final PartitionPrecreator precreator;
    private final RetentionProperties properties;
    private final StatType selectedType;

    public PartitionPrecreationScheduler(
            PartitionPrecreator precreator,
            RetentionProperties properties,
            @Value("${stats.writer.stat-type:all}") String configuredStatType) {
        this.precreator = precreator;
        this.properties = properties;
        this.selectedType = parseSelectedType(configuredStatType);
    }

    /** Uses the existing retention cron property; no second schedule is introduced. */
    @Scheduled(cron = "${stats.retention.schedule}", zone = "UTC")
    public void preCreatePartitions() {
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        for (StatType type : targetTypes()) {
            PartitionPrecreationResult result =
                    precreator.ensurePartitions(type, todayUtc, properties.preCreateDays());
            if (!result.lockAcquired()) {
                // ADR-003 D3: another Writer replica owns this type's maintenance run.
                LOGGER.debug(
                        "Skipping partition pre-creation because the advisory lock is held: statType={}",
                        type.wireName());
                continue;
            }
            LOGGER.info(
                    "Ensured daily partitions: statType={}, firstDay={}, preCreateDays={}, partitionCount={}",
                    type.wireName(),
                    todayUtc,
                    properties.preCreateDays(),
                    result.partitionsEnsured());
        }
    }

    private List<StatType> targetTypes() {
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
        if ("all".equals(wireName)) {
            return null;
        }
        return StatType.fromWireName(wireName);
    }
}
