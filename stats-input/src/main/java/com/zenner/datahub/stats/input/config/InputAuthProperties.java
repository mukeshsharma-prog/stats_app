package com.zenner.datahub.stats.input.config;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Inbound Heimdal authentication — {@code stats.input.auth}.
 *
 * <p>Inbound authentication was <em>deferred by decision</em> on 18 Aug 2026: build the
 * validate/publish/confirm path first, and make authentication a config-selected filter
 * so that ZC's eventual answer is an implementation plus a property, never a
 * rewrite of the endpoint. This record is the seam. The <em>mechanism</em> is the only
 * thing ZC's answer changes.</p>
 *
 * <p><strong>There is deliberately no default.</strong> {@code application.properties}
 * resolves the mode from {@code ${STATS_INPUT_AUTH_MODE}} with no fallback, so a
 * deployment that does not set it fails to start. {@code none} has to be typed by a
 * person, on purpose: while it is selected, anything that can reach the Input port can
 * write statistics, and that risk is acceptable on a local dev stack and nowhere else.
 * Two guards go with it and neither is optional — a WARN on every startup, and a
 * refusal to start under the {@code prod} profile.</p>
 *
 * <p>The {@code oauth2} settings are checked only when that mode is selected, and then
 * fail closed: a blank issuer or audience stops the context rather than accepting a token
 * minted for any client of the realm.</p>
 */
@ConfigurationProperties(prefix = "stats.input.auth")
public record InputAuthProperties(Mode mode, String requiredRole, OAuth2 oauth2) {

    public InputAuthProperties {
        Objects.requireNonNull(
                mode,
                "stats.input.auth.mode must be set to one of none|mtls|oauth2 — there is no "
                        + "default, so an unconfigured deployment fails to start");
        Objects.requireNonNull(requiredRole, "stats.input.auth.required-role");
        if (requiredRole.isBlank()) {
            throw new IllegalArgumentException("stats.input.auth.required-role must not be blank");
        }
        if (oauth2 == null) {
            oauth2 = new OAuth2(null, null, null);
        }
    }

    /**
     * The three mechanisms the contract admits.
     *
     * <p>Our position to ZC is OAuth2 client credentials, sent as "unless you
     * object by &lt;date&gt;, we implement OAuth2". {@link #MTLS} is here because it is
     * the other half of the question they were asked, not because it is a preference.</p>
     */
    public enum Mode {
        /** No authentication. Dev only, WARNed on every startup, refused under prod. */
        NONE,
        /** Candidate mechanism. Not implemented; selecting it fails startup rather than requests. */
        MTLS,
        /**
         * A Keycloak-issued bearer token: signature, issuer, audience and expiry validated,
         * and the realm role {@code required-role} required.
         */
        OAUTH2;

        /** Renders the value as it is written in configuration, for messages and logs. */
        public String propertyValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * {@code stats.input.auth.oauth2.*} — read only when {@link Mode#OAUTH2} is selected.
     *
     * @param issuerUri the expected {@code iss}, compared as a string. Signing keys are
     *     discovered from it unless {@code jwkSetUri} is set.
     * @param audience the client id the token must name in {@code aud}
     * @param jwkSetUri where to fetch signing keys when the issuer URL is not reachable
     *     from this process (a container on the compose network); blank means discovery
     */
    public record OAuth2(String issuerUri, String audience, String jwkSetUri) {

        /**
         * Fails startup on anything a decoder could not validate a token against.
         *
         * @throws IllegalStateException naming the key that is missing or malformed
         */
        public void requireComplete() {
            requireUri("stats.input.auth.oauth2.issuer-uri", issuerUri);
            if (audience == null || audience.isBlank()) {
                throw new IllegalStateException(
                        "stats.input.auth.oauth2.audience must be set and non-blank when "
                                + "stats.input.auth.mode=oauth2; an empty value would accept a token "
                                + "minted for any client of the realm");
            }
            if (hasJwkSetUri()) {
                requireUri("stats.input.auth.oauth2.jwk-set-uri", jwkSetUri);
            }
        }

        public boolean hasJwkSetUri() {
            return jwkSetUri != null && !jwkSetUri.isBlank();
        }

        private static void requireUri(String key, String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(
                        key + " must be set and non-blank when stats.input.auth.mode=oauth2; "
                                + "it has no default in code");
            }
            URI uri;
            try {
                uri = URI.create(value);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(key + " is not a valid URI", e);
            }
            if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
                throw new IllegalStateException(key + " must be an http or https URI");
            }
        }
    }
}
