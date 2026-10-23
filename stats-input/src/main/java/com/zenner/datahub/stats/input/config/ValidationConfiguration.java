package com.zenner.datahub.stats.input.config;

import com.zenner.datahub.stats.common.validation.StatsSchemaValidator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the shared Draft 2020-12 validator as a singleton.
 *
 * <p>It lives in {@code stats-common} because the schemas in
 * {@code docs/schemas/} are normative for the whole project and the Java oracle has
 * to stay in parity with the Python CI gate. Construction compiles and caches all six
 * schemas, so it is built once here rather than per request.</p>
 */
@Configuration
public class ValidationConfiguration {

    @Bean
    public StatsSchemaValidator statsSchemaValidator() {
        return new StatsSchemaValidator();
    }

    /**
     * The one wall clock the envelope gate reads, so that the {@code ts.server} bound
     * is testable against a fixed instant instead of the machine's.
     */
    @Bean
    public Clock inputClock() {
        return Clock.systemUTC();
    }
}
