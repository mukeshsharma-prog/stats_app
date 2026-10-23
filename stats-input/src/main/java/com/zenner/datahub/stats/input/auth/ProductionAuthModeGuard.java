package com.zenner.datahub.stats.input.auth;

import com.zenner.datahub.stats.input.config.InputAuthProperties;
import com.zenner.datahub.stats.input.config.InputAuthProperties.Mode;
import java.util.Objects;

/**
 * Refuses to build under the {@code prod} profile when ingest is unauthenticated.
 *
 * <p>A class whose only job is to throw in its constructor looks odd until you ask what
 * the alternative detects. A startup WARN is a control only if somebody is reading the
 * log at the moment of deployment. This one turns "we meant to fix that before prod"
 * into a deployment that does not start.</p>
 */
public final class ProductionAuthModeGuard {

    public ProductionAuthModeGuard(InputAuthProperties authProperties) {
        Objects.requireNonNull(authProperties, "authProperties");
        if (authProperties.mode() == Mode.NONE) {
            throw new IllegalStateException(
                    "stats.input.auth.mode=none is refused under the prod profile. Deferring "
                            + "inbound authentication was a decision for local development; "
                            + "shipping it is an open, unauthenticated write endpoint. Select an "
                            + "authenticated mode.");
        }
    }
}
