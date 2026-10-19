package com.zenner.datahub.stats.api.security;

import java.util.List;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;

/**
 * The issuer and audience the token decoder validates, refused at startup when either is
 * blank (T-038, ADR-006; DL-172).
 *
 * <p><strong>Unset and empty are not the same.</strong> An unset
 * {@code ${STATS_API_AUDIENCE}} stops the context with an unresolvable placeholder. An
 * <em>empty</em> one — {@code STATS_API_AUDIENCE=} in an {@code .env} — binds Boot's
 * {@code audiences} to an empty list, and Boot then adds no audience validator at all:
 * a token minted for any client of the realm is accepted, and nothing says so. The T-028
 * review measured it (200 for a {@code stats-input} token and for a token with no
 * {@code aud}). An empty issuer fails the other way — every token is a 401 — which is
 * safe, but an API that refuses everyone should not report itself ready. Both are
 * therefore a startup failure naming the key, never a per-request answer: a request-time
 * check would let the replica pass readiness and take traffic in a state no request can
 * fix.</p>
 */
public final class ResourceServerSettings {

    static final String ISSUER_KEY = "spring.security.oauth2.resourceserver.jwt.issuer-uri";
    static final String AUDIENCES_KEY = "spring.security.oauth2.resourceserver.jwt.audiences";

    private ResourceServerSettings() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** @throws IllegalStateException when the issuer or the audience list is blank */
    public static void require(OAuth2ResourceServerProperties properties) {
        OAuth2ResourceServerProperties.Jwt jwt = properties.getJwt();
        String issuer = jwt.getIssuerUri();
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException(ISSUER_KEY + " must be set and non-blank; it has no default in code");
        }
        List<String> audiences = jwt.getAudiences();
        if (audiences == null
                || audiences.isEmpty()
                || audiences.stream().anyMatch(audience -> audience == null || audience.isBlank())) {
            throw new IllegalStateException(
                    AUDIENCES_KEY + " must name at least one non-blank audience; an empty value disables the aud check");
        }
    }
}
