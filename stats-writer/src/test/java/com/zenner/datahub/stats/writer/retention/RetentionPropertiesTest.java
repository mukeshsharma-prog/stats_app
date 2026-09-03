package com.zenner.datahub.stats.writer.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RetentionPropertiesTest {

    @Test
    void acceptsTheExistingDailyPrecreationSettings() {
        RetentionProperties properties =
                new RetentionProperties(
                        RetentionProperties.PartitionGranularity.DAILY,
                        7,
                        "0 30 3 * * *");

        assertThat(properties.partitionGranularity())
                .isEqualTo(RetentionProperties.PartitionGranularity.DAILY);
        assertThat(properties.preCreateDays()).isEqualTo(7);
        assertThat(properties.schedule()).isEqualTo("0 30 3 * * *");
    }

    @Test
    void permitsSpringCronDisablementForControlledTests() {
        assertThat(
                        new RetentionProperties(
                                        RetentionProperties.PartitionGranularity.DAILY, 0, "-")
                                .schedule())
                .isEqualTo("-");
    }

    @Test
    void rejectsNegativePrecreationAndInvalidSchedules() {
        assertThatThrownBy(
                        () ->
                                new RetentionProperties(
                                        RetentionProperties.PartitionGranularity.DAILY,
                                        -1,
                                        "0 30 3 * * *"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero or greater");

        assertThatThrownBy(
                        () ->
                                new RetentionProperties(
                                        RetentionProperties.PartitionGranularity.DAILY,
                                        7,
                                        "not-a-cron"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid Spring cron");

        assertThatThrownBy(
                        () ->
                                new RetentionProperties(
                                        RetentionProperties.PartitionGranularity.DAILY,
                                        RetentionProperties.MAX_PRE_CREATE_DAYS + 1,
                                        "0 30 3 * * *"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed");
    }
}
