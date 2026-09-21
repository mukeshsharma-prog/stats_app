package com.zenner.datahub.stats.input.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.input.config.IngestEndpointProperties;
import com.zenner.datahub.stats.input.config.RequestLimitProperties;
import com.zenner.datahub.stats.input.ingest.IngestMetrics;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the two request bounds on the ingest paths, and only there (T-018).
 *
 * <p>Scoping them to the two {@code stats.input.endpoint.*-path} values is deliberate. A rate limit
 * covering {@code /actuator/health/readiness} would let ingest load fail the container
 * health check and take the instance out of rotation — an incident manufactured by the
 * defence against one (D13, D-10).</p>
 *
 * <p>Both ingest doors share <em>one</em> rate-limit bucket (DL-151). The limit exists
 * to protect this instance, and the instance does not have twice the capacity because
 * it has two paths; a per-door budget would let the two together exceed what the
 * publisher was sized for.</p>
 */
@Configuration(proxyBeanMethods = false)
public class RequestLimitConfiguration {

    @Bean
    public FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter(
            RequestLimitProperties limits,
            IngestEndpointProperties endpoint,
            IngestMetrics metrics,
            ObjectMapper objectMapper) {
        FilterRegistrationBean<RequestSizeLimitFilter> registration =
                new FilterRegistrationBean<>(
                        new RequestSizeLimitFilter(limits, metrics, objectMapper));
        registration.addUrlPatterns(endpoint.paths().toArray(String[]::new));
        registration.setOrder(RequestSizeLimitFilter.ORDER);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<IngestRateLimitFilter> ingestRateLimitFilter(
            RequestLimitProperties limits,
            IngestEndpointProperties endpoint,
            IngestMetrics metrics,
            ObjectMapper objectMapper) {
        FilterRegistrationBean<IngestRateLimitFilter> registration =
                new FilterRegistrationBean<>(
                        new IngestRateLimitFilter(limits, metrics, objectMapper));
        registration.addUrlPatterns(endpoint.paths().toArray(String[]::new));
        registration.setOrder(IngestRateLimitFilter.ORDER);
        return registration;
    }
}
