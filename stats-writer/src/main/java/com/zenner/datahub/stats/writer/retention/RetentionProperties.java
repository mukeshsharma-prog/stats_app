package com.zenner.datahub.stats.writer.retention;

import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

/** Existing ADR-003 partition-maintenance settings from application.properties. */
@ConfigurationProperties(prefix = "stats.retention")
public record RetentionProperties(
        PartitionGranularity partitionGranularity, int preCreateDays, String schedule) {

    /** Safety bound against accidentally issuing an unbounded series of DDL statements. */
    public static final int MAX_PRE_CREATE_DAYS = 366;

    public RetentionProperties {
        Objects.requireNonNull(partitionGranularity, "partitionGranularity");
        if (partitionGranularity != PartitionGranularity.DAILY) {
            throw new IllegalArgumentException(
                    "Only daily partition granularity is supported by ADR-003");
        }
        if (preCreateDays < 0) {
            throw new IllegalArgumentException("preCreateDays must be zero or greater");
        }
        if (preCreateDays > MAX_PRE_CREATE_DAYS) {
            throw new IllegalArgumentException(
                    "preCreateDays must not exceed " + MAX_PRE_CREATE_DAYS);
        }
        if (schedule == null
                || (!Scheduled.CRON_DISABLED.equals(schedule)
                        && !CronExpression.isValidExpression(schedule))) {
            throw new IllegalArgumentException("schedule must be a valid Spring cron expression");
        }
    }

    public enum PartitionGranularity {
        DAILY
    }
}
