package com.zenner.datahub.stats.input.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.common.error.ProblemResponseSettings;
import com.zenner.datahub.stats.input.config.IngestEndpointProperties;
import com.zenner.datahub.stats.input.config.InputAuthProperties;
import com.zenner.datahub.stats.input.ingest.IngestMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Selects the inbound authentication mechanism, and refuses the combinations that must
 * never run.
 *
 * <p><strong>Why this is a servlet filter and not Spring Security's filter chain.</strong>
 * Adding {@code spring-boot-starter-security} would change the default posture of the
 * whole application — every actuator endpoint included, and the readiness probe that
 * docker-compose health-checks with it. The ingest filter is registered on the two ingest
 * paths and nothing else, so authentication cannot leak onto the probes and the probes
 * cannot open a hole in ingest. {@code oauth2} uses Spring Security's JWT decoder and
 * validators only.</p>
 *
 * <p><strong>An unimplemented mode fails at startup, not per request.</strong>
 * Selecting {@code mtls} throws while the context is building. The alternative — accept
 * the mode and let requests through until someone implements it — produces an endpoint
 * that reports itself as authenticated and is not, which is strictly worse than
 * {@code none}, because {@code none} is at least honest.</p>
 */
@Configuration(proxyBeanMethods = false)
public class InputAuthConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InputAuthConfiguration.class);

    /**
     * The ingest authentication filter for the selected mode, on both ingest paths only.
     *
     * <p>Actuator is deliberately outside it: the container health-checks
     * {@code /actuator/health/readiness}, and an authenticated probe fails the deployment
     * rather than protecting anything. In {@code oauth2} mode a {@link JwtDecoder} bean, when
     * one exists, replaces the one built from {@code stats.input.auth.oauth2.*}; the settings
     * are validated either way.</p>
     */
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> ingestAuthenticationFilter(
            InputAuthProperties authProperties,
            IngestEndpointProperties endpointProperties,
            ObjectProvider<JwtDecoder> jwtDecoder,
            ObjectProvider<IngestMetrics> metrics,
            ObjectProvider<ObjectMapper> objectMapper,
            ObjectProvider<ProblemResponseSettings> problemSettings) {

        OncePerRequestFilter filter =
                switch (authProperties.mode()) {
                    case NONE -> {
                        warnThatIngestIsUnauthenticated(endpointProperties);
                        yield new UnauthenticatedIngestFilter();
                    }
                    case OAUTH2 -> {
                        InputAuthProperties.OAuth2 settings = authProperties.oauth2();
                        settings.requireComplete();
                        JwtDecoder decoder = jwtDecoder.getIfAvailable(() -> IngestJwtDecoders.create(settings));
                        announceBearerAuthentication(authProperties, endpointProperties);
                        yield new BearerTokenIngestFilter(
                                decoder,
                                authProperties.requiredRole(),
                                metrics.getObject(),
                                objectMapper.getObject(),
                                problemSettings.getObject());
                    }
                    case MTLS -> throw new IllegalStateException(
                            "stats.input.auth.mode="
                                    + authProperties.mode().propertyValue()
                                    + " is selected but not implemented — Heimdal's inbound authentication "
                                    + "mechanism has not been agreed, so there is no trust chain or "
                                    + "subject to implement against. Refusing to start rather than serving "
                                    + "an endpoint that reports itself as authenticated and is not.");
                };

        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        // Both ingest paths and only those: one mechanism, two doors.
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
                                + "to start in this mode");
    }

    private static void announceBearerAuthentication(
            InputAuthProperties authProperties, IngestEndpointProperties endpoint) {
        InputAuthProperties.OAuth2 settings = authProperties.oauth2();
        log.atInfo()
                .addKeyValue("event", "authentication_enabled")
                .addKeyValue("mode", authProperties.mode().propertyValue())
                .addKeyValue("issuer", settings.issuerUri())
                .addKeyValue("audience", settings.audience())
                .addKeyValue("requiredRole", authProperties.requiredRole())
                .addKeyValue("keys", settings.hasJwkSetUri() ? "jwk-set-uri" : "issuer discovery")
                .addKeyValue("internalPath", endpoint.internalPath())
                .addKeyValue("externalPath", endpoint.externalPath())
                .log("Ingest requires a bearer token with the ingest realm role on both ingest paths");
    }
}
