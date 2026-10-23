package com.zenner.datahub.stats.input.config;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The inbound HTTP surface — {@code stats.input.endpoint}.
 *
 * <p><strong>Two paths, one behaviour (21 Sep 2026).</strong> Management asked
 * for two ingest endpoints: one for <em>internal</em> callers — Heimdal Core — and one
 * for <em>external</em> callers. Both run the same gate, the same publisher, the same
 * confirm-then-2xx rule, the same size and rate bounds and the same authentication
 * filter; what differs is only the path, so that the ingress can route and restrict
 * them separately and so that every metric and log line says which door the record
 * came through ({@link IngestSource}). The split is therefore observability plus an
 * ingress rule — it is <em>not</em> a second authentication mechanism and it does
 * <em>not</em> bind the record's {@code tenant} to the caller: anything that can reach
 * the external path can write under any tenant's name. Keep that path off any shared
 * network until a token-tenant check exists.</p>
 *
 * <p>The success code is the <em>draft</em> one from
 * {@code docs/contracts/heimdal-input-contract-v1.md} §2 — "200 or 202, exact code
 * pending" — and the paths are drafts until ZC confirms them. All three are externalised so ZC's
 * answer is a property change rather than a code change, and the success code is
 * constrained to the two the contract actually admits — a deployment cannot quietly
 * start returning 204, which would tell Heimdal "accepted" with no body and no
 * agreed meaning.</p>
 */
@ConfigurationProperties(prefix = "stats.input.endpoint")
public record IngestEndpointProperties(String internalPath, String externalPath, int successStatus) {

    private static final Set<Integer> PERMITTED_SUCCESS_STATUSES = Set.of(200, 202);

    public IngestEndpointProperties {
        requireAbsolutePath(internalPath, "stats.input.endpoint.internal-path");
        requireAbsolutePath(externalPath, "stats.input.endpoint.external-path");
        if (internalPath.equals(externalPath)) {
            // One path under two names would register every filter twice and make the
            // source label a coin toss. The split exists to tell the two apart.
            throw new IllegalArgumentException(
                    "stats.input.endpoint.internal-path and .external-path must differ, both were "
                            + internalPath);
        }
        if (!PERMITTED_SUCCESS_STATUSES.contains(successStatus)) {
            throw new IllegalArgumentException(
                    "stats.input.endpoint.success-status must be 200 or 202 (contract §2), was "
                            + successStatus);
        }
    }

    /** Both ingest paths, in a fixed order, for filter registration and mappings. */
    public List<String> paths() {
        return List.of(internalPath, externalPath);
    }

    /** The path of one source. */
    public String pathOf(IngestSource source) {
        return switch (Objects.requireNonNull(source, "source")) {
            case INTERNAL -> internalPath;
            case EXTERNAL -> externalPath;
        };
    }

    /**
     * Resolves which door a request came through from its path within the servlet
     * context, or {@code null} for a path that is neither — which cannot happen for a
     * request the ingest filters and controller see, because they are registered on
     * exactly these two patterns.
     */
    public IngestSource sourceOf(String pathWithinContext) {
        if (internalPath.equals(pathWithinContext)) {
            return IngestSource.INTERNAL;
        }
        if (externalPath.equals(pathWithinContext)) {
            return IngestSource.EXTERNAL;
        }
        return null;
    }

    private static void requireAbsolutePath(String path, String property) {
        Objects.requireNonNull(path, property);
        if (path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException(property + " must be an absolute path");
        }
        if (path.startsWith("/actuator")) {
            // The ingest filters — size, rate, authentication — are registered on these
            // paths. Put one under /actuator and the readiness probe the container
            // health-checks with would be rate-limited and authenticated.
            throw new IllegalArgumentException(property + " must not be under /actuator");
        }
    }
}
