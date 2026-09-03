package com.zenner.datahub.stats.writer.retention;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled partition pre-creation owned by stats-writer (ADR-003 D1, STA-23C).
 *
 * <p>Keeps the daily horizon rolling forward once the process is up.
 * {@link PartitionBootstrap} covers the deployment moment, which this cron cannot:
 * a module deployed after the scheduled hour would otherwise wait a full day for
 * its first partition.</p>
 *
 * <p>Uses the existing {@code stats.retention.schedule} property; no second
 * schedule is introduced. Failure handling lives in
 * {@link PartitionMaintenance#ensureOnSchedule()}, which isolates each stat type so
 * one failing type cannot leave the others without partitions tomorrow.</p>
 */
@Component
public class PartitionPrecreationScheduler {

    private final PartitionMaintenance maintenance;

    public PartitionPrecreationScheduler(PartitionMaintenance maintenance) {
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
    }

    @Scheduled(cron = "${stats.retention.schedule}", zone = "UTC")
    public void preCreatePartitions() {
        maintenance.ensureOnSchedule();
    }
}
