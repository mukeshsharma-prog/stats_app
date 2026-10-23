package com.zenner.datahub.stats.input.publish;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers a {@link StatsPublisher} only when nothing else has supplied one.
 *
 * <p>The confirm-aware RabbitMQ implementation is its own bean; this
 * fallback then drops out on its own. Keeping the fallback behind
 * {@link ConditionalOnMissingBean} is what makes that an additive change rather than
 * an edit to shared code.</p>
 */
@Configuration
public class PublisherConfiguration {

    @Bean
    @ConditionalOnMissingBean(StatsPublisher.class)
    public StatsPublisher statsPublisher() {
        return new UnconfirmedStatsPublisher();
    }
}
