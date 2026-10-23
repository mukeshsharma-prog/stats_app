package com.zenner.datahub.stats.input.auth;

import com.zenner.datahub.stats.input.config.InputAuthProperties;
import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;

/**
 * Builds the decoder the ingest filter validates bearer tokens with.
 *
 * <p><strong>{@code iss} is compared as a string and never fetched when a JWK set URI is
 * given.</strong> A container on the compose network cannot reach the issuer's
 * {@code localhost} URL, so it is pointed at the keys directly and still checks that
 * {@code iss} is exactly the configured issuer. Without a JWK set URI the keys are found by
 * OIDC discovery from the issuer — the right choice in production, where the issuer is a
 * real HTTPS URL.</p>
 *
 * <p>Discovery is <strong>lazy</strong>: it happens on the first token, not at startup, so a
 * Keycloak that is briefly down does not stop Input from starting, and while it is down the
 * filter answers 503 rather than refusing valid senders.</p>
 */
public final class IngestJwtDecoders {

    private IngestJwtDecoders() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** @throws IllegalStateException when the settings are incomplete */
    public static JwtDecoder create(InputAuthProperties.OAuth2 settings) {
        settings.requireComplete();
        OAuth2TokenValidator<Jwt> validator = validator(settings.issuerUri(), settings.audience());
        if (settings.hasJwkSetUri()) {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(settings.jwkSetUri()).build();
            decoder.setJwtValidator(validator);
            return decoder;
        }
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(settings.issuerUri()).build();
            decoder.setJwtValidator(validator);
            return decoder;
        });
    }

    /**
     * Lifetime and issuer (Spring Security's defaults, with their clock-skew allowance), and
     * an {@code aud} that names this service. A token minted for another client of the same
     * realm is refused, which is what stops a neighbouring service's token being replayed here.
     */
    static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>(
                        JwtClaimNames.AUD, audiences -> audiences != null && audiences.contains(audience)));
    }
}
