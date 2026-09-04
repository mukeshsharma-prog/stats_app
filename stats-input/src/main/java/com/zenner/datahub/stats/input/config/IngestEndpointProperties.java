package com.zenner.datahub.stats.input.config;

import java.util.Objects;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The inbound HTTP surface — {@code stats.input.endpoint}.
 *
 * <p>Both values are the <em>draft</em> ones from
 * {@code docs/contracts/heimdal-input-contract-v1.md} §1/§2, which records the path
 * as "confirm against the deployed Datahub API convention" and the success code as
 * "200 or 202, exact code pending". D-03 closes both. They are externalised so ZC's
 * answer is a property change rather than a code change, and the success code is
 * constrained to the two the contract actually admits — a deployment cannot quietly
 * start returning 204, which would tell Heimdal "accepted" with no body and no
 * agreed meaning.</p>
 */
@ConfigurationProperties(prefix = "stats.input.endpoint")
public record IngestEndpointProperties(String path, int successStatus) {

    private static final Set<Integer> PERMITTED_SUCCESS_STATUSES = Set.of(200, 202);

    public IngestEndpointProperties {
        Objects.requireNonNull(path, "stats.input.endpoint.path");
        if (path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException(
                    "stats.input.endpoint.path must be an absolute path");
        }
        if (!PERMITTED_SUCCESS_STATUSES.contains(successStatus)) {
            throw new IllegalArgumentException(
                    "stats.input.endpoint.success-status must be 200 or 202 (contract §2), was "
                            + successStatus);
        }
    }
}
