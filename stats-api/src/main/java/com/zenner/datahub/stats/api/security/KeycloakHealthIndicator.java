package com.zenner.datahub.stats.api.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Readiness for the API's second real dependency — the issuer's signing keys.
 *
 * <p>Boot ships no health indicator for an OAuth2 issuer. Without one, readiness reports
 * UP while every token fails validation because the keys cannot be fetched — the API is
 * "ready" to answer every caller with a 401. This probes the same URL the decoder reads:
 * the JWK set when one is configured (Compose points it at {@code keycloak:8080}, because
 * the issuer string is not reachable from inside the container), otherwise the issuer's
 * OIDC discovery document.</p>
 *
 * <p>The detail carries only the HTTP status or the exception <em>type</em> — never a
 * message, which for a connection failure can carry a host name the probes do not need to
 * disclose. Liveness never includes this: a Keycloak outage must not restart the API.</p>
 */
public final class KeycloakHealthIndicator implements HealthIndicator {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final URI probe;
    private final HttpClient client;

    public KeycloakHealthIndicator(URI probe) {
        this.probe = Objects.requireNonNull(probe, "probe");
        this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /** The URL the decoder fetches keys from: the JWK set if set, else OIDC discovery. */
    public static URI probeFor(String issuerUri, String jwkSetUri) {
        if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            return URI.create(jwkSetUri);
        }
        String issuer = Objects.requireNonNull(issuerUri, "issuerUri");
        return URI.create(
                (issuer.endsWith("/") ? issuer : issuer + "/") + ".well-known/openid-configuration");
    }

    @Override
    public Health health() {
        HttpRequest request = HttpRequest.newBuilder(probe).timeout(TIMEOUT).GET().build();
        try {
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            Health.Builder builder = status == 200 ? Health.up() : Health.down();
            return builder.withDetail("status", status).build();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Health.down().withDetail("error", interrupted.getClass().getSimpleName()).build();
        } catch (Exception failure) {
            return Health.down().withDetail("error", failure.getClass().getSimpleName()).build();
        }
    }
}
