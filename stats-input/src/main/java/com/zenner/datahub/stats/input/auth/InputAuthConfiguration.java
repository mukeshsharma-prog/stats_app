package com.zenner.datahub.stats.input.auth;

import com.zenner.datahub.stats.input.config.IngestEndpointProperties;
import com.zenner.datahub.stats.input.config.InputAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Selects the inbound authentication mechanism, and refuses the combinations that must
 * never run (Q8a, T-017; ADR-006).
 *
 * <p><strong>Why this is a servlet filter and not Spring Security (DL-120).</strong>
 * Adding {@code spring-boot-starter-security} would change the default posture of the
 * whole application — every actuator endpoint included, and the readiness probe that
 * docker-compose health-checks with it — before the mechanism Q8a-2 will choose is
 * even known. The starter arrives <em>with</em> the OAuth2 implementation, where its
 * defaults can be reasoned about against a real requirement. What T-017 owes is the
 * seam, and a filter registered on one path is the smallest thing that is honestly a
 * seam.</p>
 *
 * <p><strong>An unimplemented mode fails at startup, not per request.</strong>
 * Selecting {@code mtls} or {@code oauth2} today throws while the context is building.
 * The alternative — accept the mode and let requests through until someone implements
 * it — produces an endpoint that reports itself as authenticated and is not, which is
 * strictly worse than {@code none}, because {@code none} is at least honest.</p>
 */
@Configuration(proxyBeanMethods = false)
public class InputAuthConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InputAuthConfiguration.class);

    @Bean
    public FilterRegistrationBean<UnauthenticatedIngestFilter> ingestAuthenticationFilter(
            InputAuthProperties authProperties, IngestEndpointProperties endpointProperties) {

        switch (authProperties.mode()) {
            case NONE -> warnThatIngestIsUnauthenticated(endpointProperties);
            case MTLS, OAUTH2 -> throw new IllegalStateException(
                    "stats.input.auth.mode="
                            + authProperties.mode().propertyValue()
                            + " is selected but not implemented — Q8a-2 has not been answered, so "
                            + "there is no issuer, audience, client identity or rotation policy to "
                            + "implement against. Refusing to start rather than serving an endpoint "
                            + "that reports itself as authenticated and is not (ADR-006).");
        }

        FilterRegistrationBean<UnauthenticatedIngestFilter> registration =
                new FilterRegistrationBean<>(new UnauthenticatedIngestFilter());
        // Both ingest paths and only those (DL-151: one mechanism, two doors). Actuator
        // is deliberately outside this: the container health-checks
        // /actuator/health/readiness, and an authenticated probe fails the deployment
        // rather than protecting anything (D13, D-10).
        registration.addUrlPatterns(endpointProperties.paths().toArray(String[]::new));
        registration.setOrder(UnauthenticatedIngestFilter.ORDER);
        return registration;
    }

    /**
     * The guard that actually keeps {@code none} out of production.
     *
     * <p>The WARN below is a courtesy to whoever is reading a log. This bean is the
     * control: under the {@code prod} profile the context refuses to build, so the
     * deployment fails at start rather than serving an open write endpoint.</p>
     */
    @Bean
    @Profile("prod")
    public ProductionAuthModeGuard productionAuthModeGuard(InputAuthProperties authProperties) {
        return new ProductionAuthModeGuard(authProperties);
    }

    private static void warnThatIngestIsUnauthenticated(IngestEndpointProperties endpoint) {
        log.atWarn()
                .addKeyValue("event", "authentication_disabled")
                .addKeyValue("internalPath", endpoint.internalPath())
                .addKeyValue("externalPath", endpoint.externalPath())
                .log(
                        "INGEST IS UNAUTHENTICATED: stats.input.auth.mode=none. This is acceptable "
                                + "on a local dev stack and nowhere else; the prod profile refuses "
                                + "to start in this mode (Q8a)");
    }
}
