package com.zenner.datahub.stats.input.publish;

import com.zenner.datahub.stats.common.model.StatType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The stand-in that holds the publish seam open when no confirming publisher is configured.
 *
 * <p>It always answers {@link PublishOutcome#NOT_CONFIRMED}, so the endpoint answers
 * 503 and Heimdal retries. That is the honest state of a module whose envelope gate
 * runs without a confirm path: a 2xx may only follow a
 * real publisher confirm. Returning a cheerful 200 from a module that publishes
 * nothing would be the worst failure this system can have — the record is gone and
 * the sender has been told it is safe, so it never sends it again.</p>
 *
 * <p>{@link PublisherConfiguration} registers this only when no other
 * {@link StatsPublisher} exists, so the confirming publisher supersedes it without editing this file.</p>
 */
public final class UnconfirmedStatsPublisher implements StatsPublisher {

    private static final Logger log = LoggerFactory.getLogger(UnconfirmedStatsPublisher.class);

    @PostConstruct
    void warnOnStartup() {
        log.atWarn()
                .addKeyValue("event", "publisher_unavailable")
                .log(
                        "stats-input has NO confirming publisher in this context: the envelope gate "
                                + " is active but nothing can publish. Every record that passes "
                                + "validation is answered 503 and nothing is published. This is deliberate "
                                + "— a 2xx follows a publisher confirm and nothing else. "
                                + "The confirming publisher exists; if you are seeing this outside "
                                + "a test, the broker configuration did not produce one.");
    }

    @Override
    public PublishOutcome publish(StatType statType, byte[] body) {
        return PublishOutcome.NOT_CONFIRMED;
    }
}
