package com.zenner.datahub.stats.api.config;

import com.zenner.datahub.stats.common.error.ProblemResponseSettings;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The problem {@code type} base and the 503 {@code Retry-After}, from
 * {@code stats.problems.*}, applied to the one mapper every API response is written with.
 */
@Configuration(proxyBeanMethods = false)
public class ProblemResponseConfiguration {

    @Bean
    public ProblemResponseSettings problemResponseSettings(
            @Value("${" + ProblemResponseSettings.TYPE_URI_BASE_KEY + "}") String typeUriBase,
            @Value("${" + ProblemResponseSettings.RETRY_AFTER_KEY + "}") Duration retryAfter) {
        return ProblemResponseSettings.of(typeUriBase, retryAfter);
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer problemTypeUriBase(ProblemResponseSettings settings) {
        return builder -> builder.postConfigurer(settings::applyTo);
    }
}
