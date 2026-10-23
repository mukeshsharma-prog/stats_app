package com.zenner.datahub.stats.api.security;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The only place the API learns which tenant a caller is.
 *
 * <p><strong>The tenant comes from the validated token and from nowhere else.</strong>
 * Not a query parameter, not a header, not the path, not a cursor. A cursor carries no
 * tenant at all: the tenant is part of what its signature covers, so a cursor presented
 * under a different tenant simply fails verification.</p>
 *
 * <p><strong>Usable means one non-blank String.</strong> An absent claim, an empty or
 * blank one, and a multi-valued one are all unusable, and an unusable claim is a 403 —
 * never "all tenants". The multi-valued case is refused rather than read as
 * {@code tenant IN (...)}: that shape lets one misconfigured user read several tenants
 * with every test still green.</p>
 */
public final class CallerTenant {

    private CallerTenant() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** The caller's tenant, or empty when the token carries no usable tenant claim. */
    public static Optional<String> of(Jwt jwt, String tenantClaim) {
        Object value = jwt.getClaims().get(tenantClaim);
        if (value instanceof String tenant && !tenant.isBlank()) {
            return Optional.of(tenant);
        }
        return Optional.empty();
    }

    /**
     * The caller's tenant for a request that has already passed {@link ReadAccess}.
     *
     * @throws TenantUnavailableException when there is none — unreachable behind the
     *     authorization rule, and still a 403 rather than a query without a tenant
     */
    public static String require(Authentication authentication, String tenantClaim) {
        if (authentication instanceof JwtAuthenticationToken token) {
            Optional<String> tenant = of(token.getToken(), tenantClaim);
            if (tenant.isPresent()) {
                return tenant.get();
            }
        }
        throw new TenantUnavailableException();
    }

    /** Whether the token carries {@code role} among its Keycloak realm roles. */
    static boolean hasRealmRole(Jwt jwt, String role) {
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (realmAccess instanceof Map<?, ?> access
                && access.get("roles") instanceof Collection<?> roles) {
            return roles.stream().anyMatch(candidate -> Objects.equals(candidate, role));
        }
        return false;
    }

    /** No usable tenant on an authenticated request: always a 403, never a scope. */
    public static final class TenantUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TenantUnavailableException() {
            super("No usable tenant claim", null, false, false);
        }
    }
}
