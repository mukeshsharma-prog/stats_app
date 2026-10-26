package com.zenner.datahub.stats.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.api.support.TestJwks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Keycloak's signing keys cannot be fetched. A token that cannot be checked is the API's
 * fault, not the caller's: 503 with {@code Retry-After}, never the 401 that tells a caller
 * with a good token to fix its credential. A token that fails before any key is needed is
 * still 401.
 *
 * <p>Nothing here reaches PostgreSQL — every request stops at authentication — so the
 * datasource points at a port nobody listens on.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = "classpath:api-test.properties",
        properties = {
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/certs",
            "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/statsdb",
            "spring.datasource.username=stats_ro",
            "spring.datasource.password=unused",
            "STATS_ERROR_TYPE_URI_BASE=https://errors.zenner.test/stats",
            "STATS_RETRY_AFTER=PT11S"
        })
class SigningKeysUnavailableIntegrationTest {

    private static final String PATH = "/v1/stats/lorastats?from=1789000000&to=1789000060";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("a well-formed token that cannot be verified is 503 with Retry-After and no challenge")
    void unverifiableTokenIsUnavailable() throws Exception {
        MockHttpServletResponse response = get("Bearer " + TestJwks.reader("keys-alpha"));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("11");
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(response.getContentType()).startsWith("application/problem+json");
        JsonNode problem = objectMapper.readTree(response.getContentAsByteArray());
        // The configured base reaches Spring Security's handlers too.
        assertThat(problem.get("type").asText()).isEqualTo("https://errors.zenner.test/stats/service_unavailable");
        assertThat(problem.get("traceId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("no token, or one that is not a JWT, is still 401: no key was needed to say so")
    void unauthenticatedIsStill401() throws Exception {
        for (String authorization : new String[] {null, "Bearer not-a-jwt"}) {
            MockHttpServletResponse response = get(authorization);
            assertThat(response.getStatus()).as(String.valueOf(authorization)).isEqualTo(401);
            assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
            assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isNull();
        }
    }

    private MockHttpServletResponse get(String authorization) throws Exception {
        var request = MockMvcRequestBuilders.get(PATH);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mvc.perform(request).andReturn().getResponse();
    }
}
