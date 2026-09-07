package com.zenner.datahub.stats.input.log;

import com.zenner.datahub.stats.input.config.IngestEndpointProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers correlation before size, authentication and rate-limit filters. */
@Configuration(proxyBeanMethods = false)
public class IngestLoggingConfiguration {

    @Bean
    public FilterRegistrationBean<IngestLoggingFilter> ingestLoggingFilter(
            IngestEndpointProperties endpointProperties) {
        FilterRegistrationBean<IngestLoggingFilter> registration =
                new FilterRegistrationBean<>(new IngestLoggingFilter());
        registration.addUrlPatterns(endpointProperties.path());
        registration.setOrder(IngestLoggingFilter.ORDER);
        return registration;
    }
}
