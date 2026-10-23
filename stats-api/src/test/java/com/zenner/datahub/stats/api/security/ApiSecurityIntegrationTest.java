package com.zenner.datahub.stats.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.api.support.ApiIntegrationTest;
import com.zenner.datahub.stats.api.support.TestJwks;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Every way into the API that must fail, failing
 * with the contract's status and problem — against real signed tokens and the real
 * resource server.
 *
 * <p><strong>401 is "who are you?"; 403 is "you may not".</strong> A token that does not
 * authenticate — absent, malformed, expired, wrong issuer, wrong audience, wrong key — is
 * 401. A token that authenticates but lacks the read role, or carries no usable tenant
 * claim, is 403: the caller proved who they are and has no tenant to be scoped to.
 * Neither is ever read as "all tenants".</p>
 */
class ApiSecurityIntegrationTest extends ApiIntegrationTest {

    private static final String PATH = "/v1/stats/lorastats?from=1789000000&to=1789000060";

    static Stream<Arguments> unauthenticated() {
        return Stream.of(
                Arguments.of("no token", null),
                Arguments.of("not a JWT", "not-a-jwt"),
                Arguments.of("expired", TestJwks.token().tenant("sec-alpha").expiresAt(Instant.now().minusSeconds(120)).sign()),
                Arguments.of("wrong issuer", TestJwks.token().tenant("sec-alpha").issuer("https://evil.test/realms/statsapp").sign()),
                Arguments.of("wrong audience", TestJwks.token().tenant("sec-alpha").audience("stats-input").sign()),
                Arguments.of("no audience", TestJwks.token().tenant("sec-alpha").audience(null).sign()),
                Arguments.of("signed with an unpublished key", TestJwks.token().tenant("sec-alpha").signedWithForeignKey().sign()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unauthenticated")
    @DisplayName("a token that does not authenticate is 401, with a Bearer challenge and a problem body")
    void unauthenticatedIs401(String reason, String token) throws Exception {
        MvcResult result = mvc.perform(get(token, PATH)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getHeader("WWW-Authenticate")).startsWith("Bearer");
        JsonNode problem = problem(result);
        assertThat(problem.get("type").asText()).isEqualTo("https://stats.example/errors/unauthorized");
        if (token != null) {
            assertThat(result.getResponse().getContentAsString()).doesNotContain(token);
        }
    }

    static Stream<Arguments> forbidden() {
        return Stream.of(
                Arguments.of("no read role", TestJwks.token().tenant("sec-alpha").roles("stats.ingest").sign()),
                Arguments.of("no realm roles at all", TestJwks.token().tenant("sec-alpha").claim("realm_access", Map.of()).sign()),
                Arguments.of("no tenant claim", TestJwks.token().sign()),
                Arguments.of("empty tenant claim", TestJwks.token().tenant("").sign()),
                Arguments.of("blank tenant claim", TestJwks.token().tenant("   ").sign()),
                Arguments.of("multi-valued tenant claim", TestJwks.token().tenant(List.of("sec-alpha", "sec-beta")).sign()),
                Arguments.of("numeric tenant claim", TestJwks.token().tenant(42).sign()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("forbidden")
    @DisplayName("an authenticated token without the role or a usable tenant is 403 — never 'all tenants'")
    void forbiddenIs403(String reason, String token) throws Exception {
        for (String path : List.of(PATH, "/v1/stats/lorastats/CLSCON-x", "/v1/stats/lorastats/04B64810652B/1789000000")) {
            MvcResult result = mvc.perform(get(token, path)).andReturn();
            assertThat(result.getResponse().getStatus()).as(path).isEqualTo(403);
            assertThat(problem(result).get("type").asText()).isEqualTo("https://stats.example/errors/forbidden");
        }
    }

    @Test
    @DisplayName("no role and no tenant are the same 403 — the body does not say which")
    void the403DoesNotSayWhy() throws Exception {
        JsonNode noRole = problem(mvc.perform(get(TestJwks.token().tenant("sec-alpha").roles("x").sign(), PATH)).andReturn());
        JsonNode noTenant = problem(mvc.perform(get(TestJwks.token().sign(), PATH)).andReturn());
        ((ObjectNode) noRole).remove("traceId");
        ((ObjectNode) noTenant).remove("traceId");
        assertThat(noRole).isEqualTo(noTenant);
    }

    @Test
    @DisplayName("a token whose audience list includes stats-api is accepted")
    void aMultiAudienceTokenIsAccepted() throws Exception {
        String token = TestJwks.token().tenant("sec-alpha").audience(List.of("account", "stats-api")).sign();
        mvc.perform(get(token, PATH)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the probes answer without a token; metrics and everything else do not")
    void onlyTheProbesAreOpen() throws Exception {
        mvc.perform(get(null, "/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get(null, "/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get(null, "/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get(null, "/actuator/info")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("readiness includes the key server, and it is UP against the real JWK set")
    void readinessProbesTheKeyServer(@Autowired KeycloakHealthIndicator keycloak) throws Exception {
        assertThat(keycloak.health().getStatus().getCode()).isEqualTo("UP");
        JsonNode health = json(mvc.perform(get(null, "/actuator/health/readiness")).andReturn());
        assertThat(health.get("status").asText()).isEqualTo("UP");
    }

    private JsonNode problem(MvcResult result) throws Exception {
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(result.getResponse().getHeader("X-Trace-Id")).isNotBlank();
        JsonNode body = json(result);
        assertThat(body.get("traceId").asText()).isEqualTo(result.getResponse().getHeader("X-Trace-Id"));
        return body;
    }
}
