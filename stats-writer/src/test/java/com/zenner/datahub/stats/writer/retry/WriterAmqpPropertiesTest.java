package com.zenner.datahub.stats.writer.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The two values that cannot be wrong quietly (T-024, G4).
 *
 * <p>A zero delay turns the retry arm into a hot loop against a database that is
 * already failing and spends the whole attempt budget in milliseconds; a
 * {@code max-retries} outside 1..3 either skips tiers that exist or names tiers that do
 * not. Neither is visible at runtime — the first looks like a burst of retries and the
 * second like a record that vanished — so both refuse to start.</p>
 */
class WriterAmqpPropertiesTest {

    @Test
    @DisplayName("each tier's delay is the one configured for it, in order")
    void delaysAreReturnedPerTier() {
        WriterAmqpProperties properties = properties(3);

        assertThat(properties.delayForTier(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.delayForTier(2)).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.delayForTier(3)).isEqualTo(Duration.ofMinutes(30));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4, 99})
    @DisplayName("max-retries outside the three tiers refuses to start")
    void maxRetriesIsBoundedByTheNumberOfTiers(int maxRetries) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties(maxRetries))
                .withMessageContaining("stats.amqp.max-retries");
    }

    @Test
    @DisplayName("a zero tier delay refuses to start — immediate requeue is never used")
    void zeroDelayIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new WriterAmqpProperties.Retry(
                                        Duration.ZERO, Duration.ofMinutes(5), Duration.ofMinutes(30)))
                .withMessageContaining("tier1-delay");
    }

    @Test
    @DisplayName("a non-positive confirm timeout refuses to start")
    void confirmTimeoutMustBePositive() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new WriterAmqpProperties(
                                        new WriterAmqpProperties.Retry(
                                                Duration.ofSeconds(30),
                                                Duration.ofMinutes(5),
                                                Duration.ofMinutes(30)),
                                        3,
                                        Duration.ZERO,
                                        Duration.ofSeconds(5)))
                .withMessageContaining("stats.amqp.confirm-timeout");
    }

    @Test
    @DisplayName("a zero unconfirmed-hand-off requeue delay refuses to start — rule 5, DL-168")
    void unconfirmedHandoffRequeueDelayMustBePositive() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new WriterAmqpProperties(
                                        new WriterAmqpProperties.Retry(
                                                Duration.ofSeconds(30),
                                                Duration.ofMinutes(5),
                                                Duration.ofMinutes(30)),
                                        3,
                                        Duration.ofSeconds(5),
                                        Duration.ZERO))
                .withMessageContaining("unconfirmed-handoff-requeue-delay");
    }

    private static WriterAmqpProperties properties(int maxRetries) {
        return new WriterAmqpProperties(
                new WriterAmqpProperties.Retry(
                        Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofMinutes(30)),
                maxRetries,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5));
    }
}
