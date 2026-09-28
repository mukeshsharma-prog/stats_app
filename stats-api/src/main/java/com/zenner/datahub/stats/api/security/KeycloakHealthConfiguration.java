package com.zenner.datahub.stats.api.security;

import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the issuer probe as the {@code keycloak} health contributor, which the
 * readiness group names (D13). {@code management.health.keycloak.enabled=false} removes
 * it — for tests that exercise authorization without a key server, never in a
 * deployment.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnEnabledHealthIndicator("keycloak")
public class KeycloakHealthConfiguration {

    @Bean
    public KeycloakHealthIndicator keycloakHealthIndicator(OAuth2ResourceServerProperties properties) {
        OAuth2ResourceServerProperties.Jwt jwt = properties.getJwt();
        return new KeycloakHealthIndicator(
                KeycloakHealthIndicator.probeFor(jwt.getIssuerUri(), jwt.getJwkSetUri()));
    }
}
