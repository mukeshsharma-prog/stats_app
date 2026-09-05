package com.zenner.datahub.stats.writer.retry;

import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Writer's broker settings — {@code stats.amqp} (T-024, G1/G4, ADR-005).
 *
 * <p><strong>Three explicit tiers, not a backoff curve.</strong> G4 fixed this on
 * 18 Aug 2026: there is no {@code retry-multiplier} and no {@code retry-max-delay}.
 * A multiplier has to be read together with a base, a cap and a jitter setting before
 * anyone can say what the third attempt waits; three durations can be read off the
 * properties file during an incident.</p>
 *
 * <p><strong>Every delay must be positive.</strong> An immediate requeue is never used
 * for a persistent failure — it is a hot loop against a database that is already
 * failing, and it spends the whole attempt budget in milliseconds.
 * {@link AmqpTopology#retryQueueArguments} refuses a non-positive delay as well, so
 * this is the first of two gates rather than the only one.</p>
 *
 * <p>{@code confirmTimeout} bounds the wait for the confirm on a <em>retry or
 * dead-letter</em> publish. That publish is the hand-off ADR-005 requires to be
 * confirmed <strong>before</strong> the original is ACKed, so an unbounded wait here
 * would hold a consumer thread and its prefetch slot indefinitely while the record is
 * safe but stuck.</p>
 */
@ConfigurationProperties(prefix = "stats.amqp")
public record WriterAmqpProperties(Retry retry, int maxRetries, Duration confirmTimeout) {

    public WriterAmqpProperties {
        Objects.requireNonNull(retry, "stats.amqp.retry");
        Objects.requireNonNull(confirmTimeout, "stats.amqp.confirm-timeout");
        if (maxRetries < 1 || maxRetries > AmqpTopology.RETRY_TIERS) {
            throw new IllegalArgumentException(
                    "stats.amqp.max-retries must be between 1 and "
                            + AmqpTopology.RETRY_TIERS
                            + " — there is one attempt per tier and there are exactly "
                            + AmqpTopology.RETRY_TIERS
                            + " tiers (G4)");
        }
        if (confirmTimeout.isZero() || confirmTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "stats.amqp.confirm-timeout must be positive — the retry/DLQ hand-off is "
                            + "ACKed only after it is confirmed (ADR-005)");
        }
    }

    /** The delay for {@code tier}, 1-based, as declared on that tier's queue. */
    public Duration delayForTier(int tier) {
        List<Duration> delays = retry.delays();
        if (tier < 1 || tier > delays.size()) {
            throw new IllegalArgumentException("Retry tier must be between 1 and " + delays.size());
        }
        return delays.get(tier - 1);
    }

    /** The three externalised tier delays. Starting values 30 s / 5 min / 30 min. */
    public record Retry(Duration tier1Delay, Duration tier2Delay, Duration tier3Delay) {

        public Retry {
            requirePositive(tier1Delay, "stats.amqp.retry.tier1-delay");
            requirePositive(tier2Delay, "stats.amqp.retry.tier2-delay");
            requirePositive(tier3Delay, "stats.amqp.retry.tier3-delay");
        }

        public List<Duration> delays() {
            return List.of(tier1Delay, tier2Delay, tier3Delay);
        }

        private static void requirePositive(Duration delay, String property) {
            Objects.requireNonNull(delay, property);
            if (delay.isZero() || delay.isNegative()) {
                throw new IllegalArgumentException(
                        property
                                + " must be positive — immediate requeue is never used for a "
                                + "persistent failure (G4, ADR-002)");
            }
        }
    }
}
