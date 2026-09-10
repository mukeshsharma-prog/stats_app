package com.zenner.datahub.stats.writer.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zenner.datahub.stats.common.model.StatType;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetentionPropertiesTest {

    private static final Duration STAGGER = Duration.ofSeconds(30);

    private static RetentionProperties properties(
            int preCreateDays, String schedule, Integer defaultDays, Map<String, Integer> byType) {
        return new RetentionProperties(
                RetentionProperties.PartitionGranularity.DAILY,
                preCreateDays,
                schedule,
                defaultDays,
                byType,
                STAGGER);
    }

    @Test
    void acceptsTheExistingDailyPrecreationSettings() {
        RetentionProperties properties = properties(7, "0 30 3 * * *", 90, Map.of());

        assertThat(properties.partitionGranularity())
                .isEqualTo(RetentionProperties.PartitionGranularity.DAILY);
        assertThat(properties.preCreateDays()).isEqualTo(7);
        assertThat(properties.schedule()).isEqualTo("0 30 3 * * *");
        assertThat(properties.defaultDays()).isEqualTo(90);
    }

    @Test
    void permitsSpringCronDisablementForControlledTests() {
        assertThat(properties(0, "-", 90, Map.of()).schedule()).isEqualTo("-");
    }

    @Test
    void rejectsNegativePrecreationAndInvalidSchedules() {
        assertThatThrownBy(() -> properties(-1, "0 30 3 * * *", 90, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero or greater");

        assertThatThrownBy(() -> properties(7, "not-a-cron", 90, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid Spring cron");

        assertThatThrownBy(
                        () ->
                                properties(
                                        RetentionProperties.MAX_PRE_CREATE_DAYS + 1,
                                        "0 30 3 * * *",
                                        90,
                                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed");
    }

    /**
     * Retention deletes data, so an absent window must stop the context rather than
     * silently adopt one. The same reasoning leaves {@code stats.input.auth.mode} with no
     * default in code.
     */
    @Test
    void refusesToStartWithoutAConfiguredRetentionWindow() {
        assertThatThrownBy(() -> properties(7, "0 30 3 * * *", null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stats.retention.default-days must be set");
    }

    /**
     * Zero would make today's own partition eligible for dropping the moment pre-creation
     * created it — the two halves of the maintenance pass fighting over the same day.
     */
    @Test
    void rejectsARetentionWindowThatWouldDropTodaysOwnPartition() {
        assertThatThrownBy(() -> properties(7, "0 30 3 * * *", 0, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1 day");

        assertThatThrownBy(
                        () ->
                                properties(
                                        7,
                                        "0 30 3 * * *",
                                        RetentionProperties.MAX_RETENTION_DAYS + 1,
                                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed");
    }

    @Test
    void perTypeOverridesWinAndEverythingElseTakesTheDefault() {
        RetentionProperties properties =
                properties(7, "0 30 3 * * *", 90, Map.of(StatType.SYSSTATS.wireName(), 30));

        assertThat(properties.retentionDaysFor(StatType.SYSSTATS)).isEqualTo(30);
        assertThat(properties.retentionDaysFor(StatType.LORASTATS)).isEqualTo(90);
    }

    /**
     * The reason the shape is a map keyed by wire name rather than
     * {@code stats.retention.<type>-days}: a mistyped flat key binds to nothing and
     * reports nothing, and the type then keeps its data for the default window with no
     * sign that anything was configured at all.
     */
    @Test
    void refusesAPerTypeOverrideThatDoesNotNameAKnownStatType() {
        assertThatThrownBy(() -> properties(7, "0 30 3 * * *", 90, Map.of("lorstats", 30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not name a known stat type");
    }

    @Test
    void appliesTheSameBoundsToAPerTypeOverrideAsToTheDefault() {
        assertThatThrownBy(
                        () ->
                                properties(
                                        7,
                                        "0 30 3 * * *",
                                        90,
                                        Map.of(StatType.SYSSTATS.wireName(), 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("days-by-type." + StatType.SYSSTATS.wireName());
    }

    /** The offset is derived from the type, so every deployment computes the same one. */
    @Test
    void staggerOffsetIsDerivedFromTheStatTypeAndIsStableAcrossDeployments() {
        RetentionProperties properties = properties(7, "0 30 3 * * *", 90, Map.of());

        assertThat(properties.staggerFor(StatType.values()[0])).isEqualTo(Duration.ZERO);
        assertThat(properties.staggerFor(StatType.values()[1])).isEqualTo(STAGGER);
        assertThat(properties.staggerFor(StatType.values()[4]))
                .isEqualTo(STAGGER.multipliedBy(4));
    }

    @Test
    void rejectsAStaggerThatWouldPushTheRunOutOfItsOffPeakWindow() {
        assertThatThrownBy(
                        () ->
                                new RetentionProperties(
                                        RetentionProperties.PartitionGranularity.DAILY,
                                        7,
                                        "0 30 3 * * *",
                                        90,
                                        Map.of(),
                                        RetentionProperties.MAX_STAGGER.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed");

        assertThatThrownBy(
                        () ->
                                new RetentionProperties(
                                        RetentionProperties.PartitionGranularity.DAILY,
                                        7,
                                        "0 30 3 * * *",
                                        90,
                                        Map.of(),
                                        Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
    }
}
