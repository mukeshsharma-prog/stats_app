package com.zenner.datahub.stats.input.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.zenner.datahub.stats.common.error.ProblemResponseSettings;
import com.zenner.datahub.stats.input.config.InputAuthProperties;
import com.zenner.datahub.stats.input.ingest.IngestMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@code stats.input.auth.mode=oauth2}: every way a request can fail authentication, and
 * the one way it passes — against the production decoder and a real JWK set.
 *
 * <p>A request that fails never reaches the rest of the chain, which is the assertion that
 * matters: no gate, no publish, no body read.</p>
 */
class BearerTokenIngestFilterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BearerTokenIngestFilter filter = filter(IngestTokens.jwkSetUri());

    @Test
    @DisplayName("a valid token with the ingest role passes through")
    void validTokenPasses() throws Exception {
        Outcome outcome = send("Bearer " + IngestTokens.heimdal());

        assertThat(outcome.passed()).isTrue();
        assertThat(outcome.response().getStatus()).isEqualTo(200);
        assertThat(registry.find(IngestMetrics.AUTH_REJECTED).counters()).isEmpty();
    }

    @Test
    @DisplayName("no Authorization header is 401 with a Bearer challenge")
    void missingTokenIsUnauthorized() throws Exception {
        Outcome outcome = send(null);

        assertRefused(outcome, 401, "unauthorized", "unauthenticated");
        assertThat(outcome.response().getHeader("WWW-Authenticate"))
                .isEqualTo(BearerTokenIngestFilter.CHALLENGE);
    }

    @Test
    @DisplayName("another scheme, or an empty bearer token, is not a bearer token")
    void otherSchemesAreUnauthorized() throws Exception {
        assertRefused(send("Basic c3RhdHM6c2VjcmV0"), 401, "unauthorized", "unauthenticated");
        assertRefused(send("Bearer "), 401, "unauthorized", "unauthenticated");
        assertRefused(send("Bearer    "), 401, "unauthorized", "unauthenticated");
    }

    @Test
    @DisplayName("the scheme is case-insensitive, as RFC 7235 requires")
    void schemeIsCaseInsensitive() throws Exception {
        assertThat(send("bearer " + IngestTokens.heimdal()).passed()).isTrue();
        assertThat(send("BEARER " + IngestTokens.heimdal()).passed()).isTrue();
    }

    @Test
    @DisplayName("a token that is not a JWT is 401 invalid_token")
    void garbageTokenIsUnauthorized() throws Exception {
        Outcome outcome = send("Bearer not-a-jwt");

        assertRefused(outcome, 401, "unauthorized", "unauthenticated");
        assertThat(outcome.response().getHeader("WWW-Authenticate"))
                .isEqualTo(BearerTokenIngestFilter.INVALID_TOKEN_CHALLENGE);
    }

    @Test
    @DisplayName("a signature from a key the issuer never published is 401")
    void foreignSignatureIsUnauthorized() throws Exception {
        assertRefused(
                send("Bearer " + IngestTokens.token().signedWithForeignKey().sign()),
                401, "unauthorized", "unauthenticated");
    }

    @Test
    @DisplayName("an expired token is 401")
    void expiredTokenIsUnauthorized() throws Exception {
        String expired = IngestTokens.token().expiresAt(Instant.now().minusSeconds(3600)).sign();

        assertRefused(send("Bearer " + expired), 401, "unauthorized", "unauthenticated");
    }

    @Test
    @DisplayName("a token from another issuer is 401, even signed with a trusted key")
    void wrongIssuerIsUnauthorized() throws Exception {
        String foreignIssuer = IngestTokens.token().issuer("https://issuer.test/realms/other").sign();

        assertRefused(send("Bearer " + foreignIssuer), 401, "unauthorized", "unauthenticated");
    }

    @Test
    @DisplayName("a token minted for another client of the realm is 401 — no replay from a neighbour")
    void wrongAudienceIsUnauthorized() throws Exception {
        assertRefused(
                send("Bearer " + IngestTokens.token().audience("stats-api").sign()),
                401, "unauthorized", "unauthenticated");
        assertRefused(
                send("Bearer " + IngestTokens.token().audience(List.of()).sign()),
                401, "unauthorized", "unauthenticated");
    }

    @Test
    @DisplayName("a token among several audiences that include this one is accepted")
    void audienceListIncludingInputPasses() throws Exception {
        String token = IngestTokens.token().audience(List.of("account", IngestTokens.AUDIENCE)).sign();

        assertThat(send("Bearer " + token).passed()).isTrue();
    }

    @Test
    @DisplayName("a valid token without the ingest role is 403")
    void missingRoleIsForbidden() throws Exception {
        Outcome readerOnly = send("Bearer " + IngestTokens.token().roles("stats.readOnly").sign());
        Outcome noRoles = send("Bearer " + IngestTokens.token().withoutRealmAccess().sign());

        assertRefused(readerOnly, 403, "forbidden", "forbidden");
        assertThat(readerOnly.response().getHeader("WWW-Authenticate"))
                .isEqualTo(BearerTokenIngestFilter.INSUFFICIENT_SCOPE_CHALLENGE);
        assertRefused(noRoles, 403, "forbidden", "forbidden");
    }

    @Test
    @DisplayName("unreachable signing keys are 503, so Heimdal retries instead of giving up")
    void unreachableKeysAreUnavailable() throws Exception {
        BearerTokenIngestFilter keysDown = filter("http://127.0.0.1:1/certs");

        Outcome outcome = send(keysDown, "Bearer " + IngestTokens.heimdal());

        assertRefused(outcome, 503, "service_unavailable", "keys_unavailable");
        assertThat(outcome.response().getHeader("WWW-Authenticate")).isNull();
        assertThat(outcome.response().getHeader("Retry-After")).isEqualTo("7");
    }

    @Test
    @DisplayName("a refusal never echoes the token")
    void refusalDoesNotEchoTheToken() throws Exception {
        String token = IngestTokens.token().roles("stats.readOnly").sign();

        Outcome outcome = send("Bearer " + token);

        assertThat(outcome.response().getContentAsString()).doesNotContain(token);
    }

    private BearerTokenIngestFilter filter(String jwkSetUri) {
        InputAuthProperties.OAuth2 settings =
                new InputAuthProperties.OAuth2(IngestTokens.ISSUER, IngestTokens.AUDIENCE, jwkSetUri);
        return new BearerTokenIngestFilter(
                IngestJwtDecoders.create(settings),
                IngestTokens.INGEST_ROLE,
                new IngestMetrics(registry),
                new ObjectMapper(),
                ProblemResponseSettings.of("https://errors.zenner.test", Duration.ofSeconds(7)));
    }

    private Outcome send(String authorization) throws Exception {
        return send(filter, authorization);
    }

    private static Outcome send(BearerTokenIngestFilter target, String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/stats");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        target.doFilter(request, response, chain);

        return new Outcome(response, chain.getRequest() != null);
    }

    private void assertRefused(Outcome outcome, int status, String code, String reason) throws Exception {
        assertThat(outcome.passed()).as("a refused request must not reach the rest of the chain").isFalse();
        assertThat(outcome.response().getStatus()).isEqualTo(status);
        assertThat(outcome.response().getContentType()).startsWith("application/problem+json");
        String body = outcome.response().getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.type")).endsWith("/" + code);
        assertThat(JsonPath.<Integer>read(body, "$.status")).isEqualTo(status);
        assertThat(registry.get(IngestMetrics.AUTH_REJECTED).tag("reason", reason).counter().count())
                .isPositive();
    }

    private record Outcome(MockHttpServletResponse response, boolean passed) {}
}
