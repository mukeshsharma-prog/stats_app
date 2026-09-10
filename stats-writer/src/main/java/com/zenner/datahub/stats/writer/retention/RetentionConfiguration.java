package com.zenner.datahub.stats.writer.retention;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one clock the retention path reads.
 *
 * <p>Retention and the late-arrival guard both answer "what is today, in UTC" and both
 * must answer it identically — the boundary between a record that is stored and one that
 * is deliberately discarded is a single day. A bean makes that boundary assertable at an
 * exact instant instead of only near one, which is what T-031 asks for: a test just inside
 * and just outside the window, not a test that is approximately either.</p>
 *
 * <p>{@link ConditionalOnMissingBean} so a test can supply a fixed clock without
 * excluding this class.</p>
 */
@Configuration(proxyBeanMethods = false)
public class RetentionConfiguration {

    /** UTC, because every partition bound and every {@code ts} member is. */
    @Bean
    @ConditionalOnMissingBean
    public Clock retentionClock() {
        return Clock.systemUTC();
    }
}
