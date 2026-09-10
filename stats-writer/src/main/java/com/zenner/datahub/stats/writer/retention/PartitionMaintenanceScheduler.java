package com.zenner.datahub.stats.writer.retention;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily partition-maintenance run owned by stats-writer — pre-creation and retention
 * (ADR-003 D1, D3, D4; STA-23A, STA-23C; T-012, T-025).
 *
 * <p>Keeps the daily horizon rolling forward and reclaims partitions past the retention
 * window. {@link PartitionBootstrap} covers the deployment moment, which this cron cannot:
 * a module deployed after the scheduled hour would otherwise wait a full day for its first
 * partition. Bootstrap deliberately does <em>not</em> run retention — a process coming up
 * should start ingesting, not start dropping tables, and D4 puts retention on a once-daily
 * off-peak schedule rather than on every restart of every replica.</p>
 *
 * <p><strong>One cron, one entry point, one order.</strong> Both halves use
 * {@code stats.retention.schedule}; no second schedule is introduced. Registering them as
 * two scheduled methods on the same expression would have left their order to whichever
 * Spring happened to find first, and the order matters — tomorrow's partition is created
 * before yesterday's is dropped. {@link PartitionMaintenance#maintainOnSchedule()} owns
 * the sequence, the per-type stagger, and the failure isolation that keeps one type's
 * problem off the other four.</p>
 */
@Component
public class PartitionMaintenanceScheduler {

    private final PartitionMaintenance maintenance;

    public PartitionMaintenanceScheduler(PartitionMaintenance maintenance) {
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
    }

    @Scheduled(cron = "${stats.retention.schedule}", zone = "UTC")
    public void maintainPartitions() {
        maintenance.maintainOnSchedule();
    }
}
