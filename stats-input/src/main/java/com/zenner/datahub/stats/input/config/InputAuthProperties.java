package com.zenner.datahub.stats.input.config;

import java.util.Locale;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Inbound Heimdal authentication — {@code stats.input.auth} (Q8a, T-017).
 *
 * <p>Q8a was answered on 18 Aug 2026 as <em>deferred by decision</em>: build the
 * validate/publish/confirm path now, and make authentication a config-selected filter
 * so that ZC's eventual answer (Q8a-2) is an implementation plus a property, never a
 * rewrite of the endpoint. This record is the seam. The <em>mechanism</em> is the only
 * thing Q8a-2 changes.</p>
 *
 * <p><strong>There is deliberately no default.</strong> {@code application.properties}
 * resolves this from {@code ${STATS_INPUT_AUTH_MODE}} with no fallback, so a
 * deployment that does not set it fails to start. {@code none} has to be typed by a
 * person, on purpose: while it is selected, anything that can reach the Input port can
 * write statistics, and that risk is acceptable on a local dev stack and nowhere else
 * (ADR-006, 2B-ANSWERS §3). Two guards go with it and neither is optional — a WARN on
 * every startup, and a refusal to start under the {@code prod} profile.</p>
 */
@ConfigurationProperties(prefix = "stats.input.auth")
public record InputAuthProperties(Mode mode, String requiredRole) {

    public InputAuthProperties {
        Objects.requireNonNull(
                mode,
                "stats.input.auth.mode must be set to one of none|mtls|oauth2 — there is no "
                        + "default, so an unconfigured deployment fails to start (Q8a)");
        Objects.requireNonNull(requiredRole, "stats.input.auth.required-role");
        if (requiredRole.isBlank()) {
            throw new IllegalArgumentException("stats.input.auth.required-role must not be blank");
        }
    }

    /**
     * The three mechanisms the contract admits.
     *
     * <p>Our position on Q8a-2 is OAuth2 client credentials, sent to ZC as "unless you
     * object by &lt;date&gt;, we implement OAuth2". {@link #MTLS} is here because it is
     * the other half of the question they were asked, not because it is a preference.</p>
     */
    public enum Mode {
        /** No authentication. Dev only, WARNed on every startup, refused under prod. */
        NONE,
        /** Q8a-2 candidate. Not implemented; selecting it fails startup rather than requests. */
        MTLS,
        /** Q8a-2 candidate and our recommendation. Not implemented; selecting it fails startup. */
        OAUTH2;

        /** Renders the value as it is written in configuration, for messages and logs. */
        public String propertyValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
