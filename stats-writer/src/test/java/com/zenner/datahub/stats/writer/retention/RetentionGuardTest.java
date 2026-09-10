package com.zenner.datahub.stats.writer.retention;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.common.model.StatsEnvelope;
import com.zenner.datahub.stats.writer.validation.ValidatedRecord;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * T-026 — late arrival is skipped, metered and acknowledged, never dead-lettered
 * (STA-23B; ADR-003 option B; D5b → DL-112).
 */
class RetentionGuardTest {

    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final int DEFAULT_DAYS = 90;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RetentionMetrics metrics = new RetentionMetrics(registry);

    @BeforeEach
    void registerTheCountersTheWayStartupDoes() {
        // PartitionMaintenance.ensureBeforeIngestion() does this for the deployment's own
        // types, so the series exports at zero rather than appearing on its first
        // occurrence. Asserting "nothing was counted" is only meaningful against a
        // counter that exists.
        metrics.registerFor(StatType.LORASTATS);
    }

    private RetentionGuard guard() {
        RetentionProperties properties =
                new RetentionProperties(
                        RetentionProperties.PartitionGranularity.DAILY,
                        7,
                        "0 30 3 * * *",
                        DEFAULT_DAYS,
                        Map.of(),
                        Duration.ZERO);
        return new RetentionGuard(
                new RetentionWindow(properties, Clock.fixed(NOW, ZoneOffset.UTC)), metrics);
    }

    /** A record whose {@code ts.device} is exactly {@code tsDevice}, in Unix seconds. */
    private static ValidatedRecord recordMeasuredAt(Instant tsDevice) throws Exception {
        String json =
                """
                {
                  "uid":"CLSCON-retention-guard", "tenant":"tenant-alpha",
                  "maintenant":"management-parent", "clsbox":"A1B2C3D4E5F6",
                  "type":"lorastats",
                  "ts":{"server":%d,"device":%d},
                  "data":{}
                }
                """
                        .formatted(NOW.getEpochSecond(), tsDevice.getEpochSecond());
        StatsEnvelope envelope =
                new ObjectMapper().readValue(json.getBytes(UTF_8), StatsEnvelope.class);
        return ValidatedRecord.of(envelope, "v1");
    }

    private double expiredCount() {
        return registry
                .get(RetentionMetrics.EXPIRED_ON_ARRIVAL)
                .tag("type", StatType.LORASTATS.wireName())
                .counter()
                .count();
    }

    @Test
    void admitsARecordInsideItsWindowAndCountsNothing() throws Exception {
        RetentionGuard guard = guard();

        assertThat(guard.admit(StatType.LORASTATS, recordMeasuredAt(NOW.minus(Duration.ofDays(30)))))
                .isTrue();
        assertThat(expiredCount()).isZero();
    }

    /**
     * The boundary, from the guard's side. One second decides whether a record is stored
     * or deliberately discarded, so it is asserted at exactly that second rather than
     * near it.
     */
    @Test
    void skipsTheInstantBeforeTheCutoffAndKeepsTheCutoffItself() throws Exception {
        RetentionGuard guard = guard();
        Instant cutoff = Instant.parse("2026-06-12T00:00:00Z");

        assertThat(guard.admit(StatType.LORASTATS, recordMeasuredAt(cutoff))).isTrue();
        assertThat(guard.admit(StatType.LORASTATS, recordMeasuredAt(cutoff.minusSeconds(1))))
                .isFalse();
        assertThat(expiredCount()).isEqualTo(1.0d);
    }

    /**
     * The gateway-back-from-an-outage case that STA-23B describes and DL-106 confirmed is
     * real: {@code ts.device} 95 days old while {@code ts.server} is current. The fixture
     * {@code edge/lorastats-late-arrival.json} is this record.
     */
    @Test
    void skipsARecordFromAGatewayThatHasBeenOfflineForMonths() throws Exception {
        RetentionGuard guard = guard();

        assertThat(guard.admit(StatType.LORASTATS, recordMeasuredAt(NOW.minus(Duration.ofDays(95)))))
                .isFalse();
        assertThat(expiredCount()).isEqualTo(1.0d);
    }

    /**
     * Never throws. An exception here would reach LorastatsConsumer's catch block, be
     * classified by FailureClassifier and routed to the retry arm — putting a valid
     * record into a dead-letter queue that never expires, where every redrive would land
     * back on this line older than before.
     */
    @Test
    void neverThrowsForALateRecordBecauseTheDeliveryMustStillBeAcknowledged() throws Exception {
        RetentionGuard guard = guard();

        assertThat(guard.admit(StatType.LORASTATS, recordMeasuredAt(Instant.EPOCH))).isFalse();
    }

    /**
     * The counter is per type, and it is the only machine-readable trace of a deliberate
     * data-loss path. Alerting on it is T-050; exporting it is this.
     */
    @Test
    void countsPerStatTypeSoOneTypesBacklogIsAttributable() throws Exception {
        RetentionGuard guard = guard();
        Instant tooOld = NOW.minus(Duration.ofDays(120));

        guard.admit(StatType.LORASTATS, recordMeasuredAt(tooOld));
        guard.admit(StatType.LORASTATS, recordMeasuredAt(tooOld));

        assertThat(expiredCount()).isEqualTo(2.0d);
        assertThat(
                        registry.find(RetentionMetrics.EXPIRED_ON_ARRIVAL)
                                .tag("type", StatType.SYSSTATS.wireName())
                                .counter())
                .isNull();
    }
}
