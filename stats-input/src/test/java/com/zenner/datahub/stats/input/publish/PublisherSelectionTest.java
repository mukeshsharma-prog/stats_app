package com.zenner.datahub.stats.input.publish;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.input.StatsInputApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * The stand-in is gone, and there is exactly one publisher.
 *
 * <p>{@link UnconfirmedStatsPublisher} answers {@code NOT_CONFIRMED} to everything and
 * warns loudly at startup. Now that the confirming publisher exists, its continued presence would be
 * a module that 503s every record while reporting a reason that is no longer true, so
 * "it drops out on its own" needs to be asserted rather than assumed.</p>
 *
 * <p><strong>Why this test is not redundant.</strong> The mechanism is
 * {@code @ConditionalOnMissingBean} on a <em>user</em> configuration class, and that
 * condition is evaluated in configuration-registration order — which for a component
 * scan follows class name. {@code InputAmqpConfiguration} currently sorts before
 * {@code PublisherConfiguration} and the fallback therefore sees the real bean. Rename
 * either class and the ordering can invert silently, leaving a module that starts
 * cleanly and publishes nothing. This test is what makes that a build failure.</p>
 */
@SpringBootTest(classes = StatsInputApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {"STATS_INPUT_AUTH_MODE=none", "STATS_INGEST_ROLE=stats.ingest"})
class PublisherSelectionTest {

    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("the confirming publisher is the only StatsPublisher in the context")
    void confirmingPublisherSupersedesTheStandIn() {
        assertThat(context.getBeansOfType(StatsPublisher.class).values())
                .singleElement()
                .isInstanceOf(ConfirmingStatsPublisher.class);
        assertThat(context.getBeansOfType(UnconfirmedStatsPublisher.class)).isEmpty();
    }
}
