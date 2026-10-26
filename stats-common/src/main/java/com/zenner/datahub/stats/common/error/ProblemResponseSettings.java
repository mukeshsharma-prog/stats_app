package com.zenner.datahub.stats.common.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Objects;

/**
 * How a module answers with a problem: the base its {@code type} URIs are published
 * under, and the {@code Retry-After} it sends with every 503.
 *
 * <p>Both are deployment configuration, not code. The type URI's host belongs to whoever
 * runs the ingress (openapi.yaml {@code servers.host}), and {@link ErrorCode#TYPE_URI_BASE}
 * is only the value a problem is built with. {@link #applyTo(ObjectMapper)} makes a
 * module's mapper write every {@code type} under the configured base instead; the
 * wire code at the end of the URI, which callers match on, never changes.</p>
 *
 * <p>Plain Java, like the rest of this module: each application binds
 * {@link #TYPE_URI_BASE_KEY} and {@link #RETRY_AFTER_KEY} and passes the values to
 * {@link #of(String, Duration)}, which refuses a blank or unusable value rather than
 * guessing one.</p>
 */
public record ProblemResponseSettings(URI typeUriBase, Duration retryAfter) {

    public static final String TYPE_URI_BASE_KEY = "stats.problems.type-uri-base";
    public static final String RETRY_AFTER_KEY = "stats.problems.retry-after";

    /** The serialization attribute {@link ProblemTypeUriSerializer} reads the settings from. */
    static final String SERIALIZATION_ATTRIBUTE = ProblemResponseSettings.class.getName();

    public ProblemResponseSettings {
        Objects.requireNonNull(typeUriBase, TYPE_URI_BASE_KEY);
        Objects.requireNonNull(retryAfter, RETRY_AFTER_KEY);
        typeUriBase = requireUsableBase(typeUriBase);
        if (retryAfter.compareTo(Duration.ofSeconds(1)) < 0 || retryAfter.toNanosPart() != 0) {
            // The header carries whole seconds (RFC 9110 §10.2.3); a fraction would be
            // silently truncated, and zero tells a sender to retry immediately.
            throw new IllegalArgumentException(
                    RETRY_AFTER_KEY + " must be a whole number of seconds, at least 1s; was " + retryAfter);
        }
    }

    /**
     * The settings from their configured text.
     *
     * @throws IllegalArgumentException naming the key whose value is blank or unusable
     */
    public static ProblemResponseSettings of(String typeUriBase, Duration retryAfter) {
        if (typeUriBase == null || typeUriBase.isBlank()) {
            throw new IllegalArgumentException(TYPE_URI_BASE_KEY + " must be set and non-blank");
        }
        try {
            return new ProblemResponseSettings(new URI(typeUriBase.strip()), retryAfter);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(TYPE_URI_BASE_KEY + " is not a valid URI", e);
        }
    }

    /** The {@code Retry-After} header value: delay-seconds. */
    public String retryAfterHeader() {
        return Long.toString(retryAfter.toSeconds());
    }

    /** The type URI {@code code} is published under. */
    public URI typeUri(ErrorCode code) {
        return URI.create(typeUriBase + "/" + Objects.requireNonNull(code, "code").wireCode());
    }

    /**
     * Makes {@code mapper} write every problem {@code type} under {@link #typeUriBase()}.
     * Attributes already set on the mapper are kept.
     */
    public void applyTo(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        mapper.setDefaultAttributes(
                mapper.getSerializationConfig()
                        .getAttributes()
                        .withSharedAttribute(SERIALIZATION_ATTRIBUTE, this));
    }

    private static URI requireUsableBase(URI base) {
        if (!base.isAbsolute() || base.getHost() == null) {
            throw new IllegalArgumentException(TYPE_URI_BASE_KEY + " must be an absolute URI with a host");
        }
        if (!"https".equals(base.getScheme()) && !"http".equals(base.getScheme())) {
            throw new IllegalArgumentException(TYPE_URI_BASE_KEY + " must be an http or https URI");
        }
        if (base.getRawQuery() != null || base.getRawFragment() != null) {
            throw new IllegalArgumentException(TYPE_URI_BASE_KEY + " must not carry a query or fragment");
        }
        String text = base.toString();
        return text.endsWith("/") ? URI.create(text.substring(0, text.length() - 1)) : base;
    }
}
