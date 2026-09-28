package com.zenner.datahub.stats.api.security;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Who may read (T-039, ADR-006): an authenticated token that holds the read role
 * <em>and</em> carries a usable tenant claim. Either missing is a 403.
 *
 * <p>Both halves are decided here, before any controller runs, so no handler can be
 * reached by a caller with no tenant to be scoped to. The role is read from the token's
 * {@code realm_access.roles} directly rather than from Spring's granted authorities: the
 * authority mapping is a second configuration surface, and one that a test using a
 * mocked authentication would bypass without anyone noticing.</p>
 */
public final class ReadAccess implements AuthorizationManager<RequestAuthorizationContext> {

    private final SecurityProperties properties;

    public ReadAccess(SecurityProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    @SuppressWarnings("deprecation") // still AuthorizationManager's one abstract method in 6.x
    public AuthorizationDecision check(
            Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        return new AuthorizationDecision(permits(authentication.get()));
    }

    boolean permits(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || !token.isAuthenticated()) {
            return false;
        }
        return CallerTenant.hasRealmRole(token.getToken(), properties.readRole())
                && CallerTenant.of(token.getToken(), properties.tenantClaim()).isPresent();
    }
}
