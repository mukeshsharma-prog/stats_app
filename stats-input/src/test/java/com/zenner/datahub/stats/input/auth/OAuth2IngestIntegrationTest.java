package com.zenner.datahub.stats.input.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.input.publish.PublishOutcome;
import com.zenner.datahub.stats.input.publish.StatsPublisher;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The whole application in {@code oauth2} mode: both ingest doors require a token with the
 * ingest role, a refused request publishes nothing, and the probes stay open.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OAuth2IngestIntegrationTest {

    private static final String RECORD = "valid/lorastats-production-canonical.json";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private StatsPublisher publisher;

    @DynamicPropertySource
    static void oauth2(DynamicPropertyRegistry registry) {
        registry.add("STATS_INPUT_AUTH_MODE", () -> "oauth2");
        registry.add("STATS_INGEST_ROLE", () -> IngestTokens.INGEST_ROLE);
        registry.add("STATS_INPUT_OAUTH2_ISSUER_URI", () -> IngestTokens.ISSUER);
        registry.add("STATS_INPUT_OAUTH2_AUDIENCE", () -> IngestTokens.AUDIENCE);
        registry.add("STATS_INPUT_OAUTH2_JWK_SET_URI", IngestTokens::jwkSetUri);
    }

    @BeforeEach
    void confirmEveryPublish() {
        clearInvocations(publisher);
        when(publisher.publish(eq(StatType.LORASTATS), any())).thenReturn(PublishOutcome.CONFIRMED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/internal/stats", "/external/stats"})
    @DisplayName("a token with the ingest role is accepted on both doors")
    void validTokenIsAccepted(String path) throws Exception {
        mockMvc.perform(record(path).header("Authorization", "Bearer " + IngestTokens.heimdal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));

        verify(publisher).publish(eq(StatType.LORASTATS), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/internal/stats", "/external/stats"})
    @DisplayName("no token is 401 on both doors, and nothing is published")
    void missingTokenIsRefused(String path) throws Exception {
        mockMvc.perform(record(path))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", BearerTokenIngestFilter.CHALLENGE))
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.type").value("https://stats.example/errors/unauthorized"));

        verify(publisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("a read-API token is 403 at ingest, and nothing is published")
    void readerTokenIsForbidden() throws Exception {
        String reader = IngestTokens.token().roles("stats.readOnly").sign();

        mockMvc.perform(record("/internal/stats").header("Authorization", "Bearer " + reader))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://stats.example/errors/forbidden"));

        verify(publisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("an oversized body is still 413 before authentication looks at it")
    void sizeLimitRunsFirst() throws Exception {
        byte[] oversized = new byte[300 * 1024];
        Arrays.fill(oversized, (byte) ' ');

        mockMvc.perform(post("/internal/stats").contentType(MediaType.APPLICATION_JSON).content(oversized))
                .andExpect(status().isPayloadTooLarge());

        verify(publisher, never()).publish(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/info"})
    @DisplayName("actuator needs no token: the filter covers the ingest paths only")
    void actuatorStaysOpen(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder record(String path) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(fixture(RECORD));
    }

    private static byte[] fixture(String name) {
        try (InputStream in =
                OAuth2IngestIntegrationTest.class.getClassLoader().getResourceAsStream(name)) {
            return Objects.requireNonNull(in, name).readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
