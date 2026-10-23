package com.zenner.datahub.stats.input.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The clock-skew rule is one-sided, and both sides are asserted here. */
class ClockSkewPolicyTest {

    private static final Instant SERVER = Instant.ofEpochSecond(1_787_054_400L);
    private final ClockSkewPolicy policy = new ClockSkewPolicy(Duration.ofMinutes(5));

    @Test
    @DisplayName("a device clock inside the tolerance is accepted")
    void acceptsInsideTolerance() {
        assertThat(policy.exceeded(SERVER.plus(Duration.ofMinutes(4)), SERVER)).isFalse();
    }

    @Test
    @DisplayName("exactly at the tolerance is accepted — a boundary that rejects surprises people")
    void acceptsExactlyAtTolerance() {
        assertThat(policy.exceeded(SERVER.plus(Duration.ofMinutes(5)), SERVER)).isFalse();
    }

    @Test
    @DisplayName("one second past the tolerance is rejected")
    void rejectsJustPastTolerance() {
        assertThat(policy.exceeded(SERVER.plus(Duration.ofMinutes(5)).plusSeconds(1), SERVER))
                .isTrue();
    }

    @Test
    @DisplayName("an hour ahead is rejected — the production clock-skew fixture")
    void rejectsAnHourAhead() {
        assertThat(policy.exceeded(SERVER.plus(Duration.ofHours(1)), SERVER)).isTrue();
    }

    @Test
    @DisplayName("a device clock BEHIND the server is accepted, however far behind")
    void acceptsArbitrarilyOldRecords() {
        // An offline gateway backfilling months of readings is the case retention on
        // ts.device exists for. Rejecting the past here would discard
        // exactly the records operations most needs; late arrival is the Writer's
        // concern, not the HTTP boundary's.
        assertThat(policy.exceeded(SERVER.minus(Duration.ofDays(400)), SERVER)).isFalse();
    }

    @Test
    @DisplayName("a zero tolerance is legal and rejects any lead at all")
    void supportsZeroTolerance() {
        ClockSkewPolicy strict = new ClockSkewPolicy(Duration.ZERO);
        assertThat(strict.exceeded(SERVER, SERVER)).isFalse();
        assertThat(strict.exceeded(SERVER.plusSeconds(1), SERVER)).isTrue();
    }

    @Test
    @DisplayName("a negative tolerance is a configuration error, not a silent inversion")
    void rejectsNegativeTolerance() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ClockSkewPolicy(Duration.ofMinutes(-1)));
    }
}
