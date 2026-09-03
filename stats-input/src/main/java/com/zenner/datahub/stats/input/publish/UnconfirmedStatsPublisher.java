package com.zenner.datahub.stats.input.publish;

import com.zenner.datahub.stats.common.model.StatType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The stand-in that holds the publish seam open until T-014 lands.
 *
 * <p>It always answers {@link PublishOutcome#NOT_CONFIRMED}, so the endpoint answers
 * 503 and Heimdal retries. That is the honest state of the module today: the envelope
 * gate is built and the confirm path is not, and rule 8 says a 2xx may only follow a
 * real publisher confirm. Returning a cheerful 200 from a module that publishes
 * nothing would be the worst failure this system can have — the record is gone and
 * the sender has been told it is safe, so it never sends it again.</p>
 *
 * <p>{@link PublisherConfiguration} registers this only when no other
 * {@link StatsPublisher} exists, so T-014 supersedes it without editing this file.</p>
 */
public final class UnconfirmedStatsPublisher implements StatsPublisher {

    private static final Logger log = LoggerFactory.getLogger(UnconfirmedStatsPublisher.class);

    @PostConstruct
    void warnOnStartup() {
        log.warn(
                "stats-input has NO publisher implementation: the envelope gate (T-013) is active "
                        + "but publishing with confirms (T-014) is not built. Every record that "
                        + "passes validation is answered 503 and nothing is published. This is "
                        + "deliberate — a 2xx follows a publisher confirm and nothing else "
                        + "(STA-04A, rule 8).");
    }

    @Override
    public PublishOutcome publish(StatType statType, byte[] body) {
        return PublishOutcome.NOT_CONFIRMED;
    }
}
