package com.zenner.datahub.stats.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * {@link ReadAccess} on its own, both halves.
 *
 * <p>Through HTTP, the controller's {@code CallerTenant.require} answers the same 403 when
 * the tenant half here is missing, so the integration suite cannot tell this layer from
 * that one — a mutation that dropped the tenant check from {@code ReadAccess} survived all
 * 267 tests. This pins the first layer by itself.</p>
 */
class ReadAccessTest {

    private static final SecurityProperties PROPERTIES =
            new SecurityProperties("stats.readOnly", "tenant", false);
    private static final ReadAccess ACCESS = new ReadAccess(PROPERTIES);

    @Test
    @DisplayName("the read role and one non-blank String tenant: permitted")
    void roleAndTenantArePermitted() {
        assertThat(ACCESS.permits(token(List.of("stats.readOnly"), "tenant-alpha"))).isTrue();
    }

    @Test
    @DisplayName("the read role without a usable tenant claim: refused here, not only in the controller")
    void theRoleAloneIsRefused() {
        assertThat(ACCESS.permits(token(List.of("stats.readOnly"), null))).isFalse();
        assertThat(ACCESS.permits(token(List.of("stats.readOnly"), " "))).isFalse();
        assertThat(ACCESS.permits(token(List.of("stats.readOnly"), List.of("tenant-alpha")))).isFalse();
    }

    @Test
    @DisplayName("a tenant without the read role: refused")
    void theTenantAloneIsRefused() {
        assertThat(ACCESS.permits(token(List.of("stats.ingest"), "tenant-alpha"))).isFalse();
    }

    @Test
    @DisplayName("anything but an authenticated JWT: refused")
    void otherAuthenticationsAreRefused() {
        assertThat(ACCESS.permits(null)).isFalse();
        assertThat(ACCESS.permits(new TestingAuthenticationToken("someone", "x", "stats.readOnly"))).isFalse();
        JwtAuthenticationToken unauthenticated = token(List.of("stats.readOnly"), "tenant-alpha");
        unauthenticated.setAuthenticated(false);
        assertThat(ACCESS.permits(unauthenticated)).isFalse();
    }

    private static JwtAuthenticationToken token(List<String> roles, Object tenant) {
        Jwt.Builder jwt =
                Jwt.withTokenValue("token")
                        .header("alg", "RS256")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .claim("realm_access", Map.of("roles", roles));
        if (tenant != null) {
            jwt.claim("tenant", tenant);
        }
        return new JwtAuthenticationToken(jwt.build(), List.of());
    }
}
