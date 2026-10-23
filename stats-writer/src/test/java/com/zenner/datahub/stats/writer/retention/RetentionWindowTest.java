package com.zenner.datahub.stats.writer.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.common.model.StatType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The cutoff arithmetic, at the boundary (the unit half of the boundary tests).
 *
 * <p>The assertions that matter are the ones one second either side of the line. The
 * arithmetic is easy to get right by an off-by-one and impossible to notice: a window a
 * day too short deletes records the business asked to keep, and nothing reports it.</p>
 */
class RetentionWindowTest {

    /** Midday, so nothing here passes by accident on a midnight boundary. */
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

    private static final int DEFAULT_DAYS = 90;

    private static RetentionWindow window(Map<String, Integer> byType) {
        RetentionProperties properties =
                new RetentionProperties(
                        RetentionProperties.PartitionGranularity.DAILY,
                        7,
                        "0 30 3 * * *",
                        DEFAULT_DAYS,
                        byType,
                        Duration.ZERO);
        return new RetentionWindow(properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static RetentionWindow window() {
        return window(Map.of());
    }

    @Test
    void theCutoffIsMidnightUtcExactlyTheWindowBeforeToday() {
        assertThat(window().cutoffDay(StatType.LORASTATS)).isEqualTo(LocalDate.of(2026, 6, 12));
        assertThat(window().cutoff(StatType.LORASTATS))
                .isEqualTo(Instant.parse("2026-06-12T00:00:00Z"));
    }

    /**
     * The boundary belongs to the retained side. A record measured at the cutoff instant
     * is exactly {@code retentionDays} old and is still inside the window the business
     * asked for; only the instant before it is outside.
     */
    @Test
    void aRecordOnTheCutoffIsKeptAndTheInstantBeforeItIsNot() {
        RetentionWindow window = window();
        Instant cutoff = window.cutoff(StatType.LORASTATS);

        assertThat(window.isExpiredOnArrival(StatType.LORASTATS, cutoff)).isFalse();
        assertThat(window.isExpiredOnArrival(StatType.LORASTATS, cutoff.plusSeconds(1))).isFalse();
        assertThat(window.isExpiredOnArrival(StatType.LORASTATS, cutoff.minusSeconds(1))).isTrue();
    }

    /**
     * The guard and the sweep must agree at the boundary, or a record is admitted into a
     * partition retention has just dropped: the insert fails, the record dead-letters,
     * and every redrive re-derives the identical verdict forever.
     */
    @Test
    void everyInstantTheGuardAdmitsFallsInsideAPartitionTheSweepKeeps() {
        RetentionWindow window = window();
        Instant cutoff = window.cutoff(StatType.LORASTATS);
        LocalDate cutoffDay = window.cutoffDay(StatType.LORASTATS);

        // Just admitted -> its day is kept.
        assertThat(window.isDroppable(StatType.LORASTATS, cutoffDay)).isFalse();
        // Just refused -> its day is dropped.
        assertThat(
                        window.isDroppable(
                                StatType.LORASTATS,
                                cutoff.minusSeconds(1).atZone(ZoneOffset.UTC).toLocalDate()))
                .isTrue();
    }

    /**
     * Retention never fights pre-creation over the same day. With any legal window,
     * today's partition and every future one are outside the droppable set.
     */
    @Test
    void neverDropsTodayOrAFuturePartition() {
        RetentionWindow window = window();
        LocalDate today = window.today();

        assertThat(window.isDroppable(StatType.LORASTATS, today)).isFalse();
        assertThat(window.isDroppable(StatType.LORASTATS, today.plusDays(7))).isFalse();
        assertThat(window.isDroppable(StatType.LORASTATS, today.minusDays(1))).isFalse();
    }

    /** The window keeps {@code retentionDays + 1} daily partitions: today plus the window. */
    @Test
    void keepsTheWindowPlusTodaysOwnPartition() {
        RetentionWindow window = window();
        LocalDate today = window.today();

        assertThat(window.isDroppable(StatType.LORASTATS, today.minusDays(DEFAULT_DAYS)))
                .isFalse();
        assertThat(window.isDroppable(StatType.LORASTATS, today.minusDays(DEFAULT_DAYS + 1)))
                .isTrue();
    }

    @Test
    void aPerTypeOverrideMovesThatTypesCutoffAndNoOthers() {
        RetentionWindow window = window(Map.of(StatType.SYSSTATS.wireName(), 30));

        assertThat(window.cutoffDay(StatType.SYSSTATS)).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(window.cutoffDay(StatType.LORASTATS)).isEqualTo(LocalDate.of(2026, 6, 12));
        assertThat(window.retentionDaysFor(StatType.SYSSTATS)).isEqualTo(30);
    }

    /**
     * Partition bounds are UTC, so the day has to be. Under a host zone far enough ahead
     * the local date is already tomorrow, and a cutoff computed from it would drop a
     * partition a day early — once, quietly, on whichever host happened to be misconfigured.
     */
    @Test
    void resolvesTodayInUtcRatherThanTheHostZone() {
        RetentionProperties properties =
                new RetentionProperties(
                        RetentionProperties.PartitionGranularity.DAILY,
                        7,
                        "0 30 3 * * *",
                        DEFAULT_DAYS,
                        Map.of(),
                        Duration.ZERO);

        RetentionWindow shifted =
                new RetentionWindow(properties, Clock.fixed(NOW, ZoneOffset.ofHours(14)));

        assertThat(shifted.today()).isEqualTo(LocalDate.of(2026, 9, 10));
    }
}
