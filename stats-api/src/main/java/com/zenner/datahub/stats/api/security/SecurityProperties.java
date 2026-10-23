package com.zenner.datahub.stats.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The two security values the resource server itself does not know about.
 *
 * <p>Issuer and audience are Boot's own properties
 * ({@code spring.security.oauth2.resourceserver.jwt.issuer-uri} and {@code .audiences}),
 * so the token's {@code iss} and {@code aud} are validated by the decoder before anything
 * here runs. These two are what the API adds on top: the role a caller must hold, and the
 * claim its tenant is read from. Both are values awaiting ZC's confirmation, which
 * is exactly why they are properties — and why neither has a default in code: an unset
 * read role would otherwise be a guessed security posture.</p>
 *
 * @param readRole the Keycloak realm role required on every read ({@code stats.readOnly})
 * @param tenantClaim the token claim holding the caller's single tenant ({@code tenant})
 * @param prometheusPublicAccess whether the local scraper can access only the metrics endpoint
 */
@ConfigurationProperties(prefix = "stats.security")
public record SecurityProperties(String readRole, String tenantClaim, boolean prometheusPublicAccess) {

    public SecurityProperties {
        requireText(readRole, "stats.security.read-role");
        requireText(tenantClaim, "stats.security.tenant-claim");
    }

    private static void requireText(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must be set; it has no default in code");
        }
    }
}
