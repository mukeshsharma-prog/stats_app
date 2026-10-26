package com.zenner.datahub.stats.input.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zenner.datahub.stats.input.publish.PublishOutcome;
import com.zenner.datahub.stats.input.publish.StatsPublisher;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code stats.problems.*} reaches every way Input answers with a problem: the
 * controller, a servlet filter, and the 503 that tells Heimdal when to resend.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "STATS_INPUT_AUTH_MODE=none",
            "STATS_INGEST_ROLE=stats.ingest",
            "STATS_ERROR_TYPE_URI_BASE=https://errors.zenner.test/stats/",
            "STATS_RETRY_AFTER=PT9S"
        })
class ProblemResponseSettingsIntegrationTest {

    private static final String BASE = "https://errors.zenner.test/stats/";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private StatsPublisher publisher;

    @Test
    @DisplayName("a controller problem carries the configured type base")
    void controllerProblem() throws Exception {
        mockMvc.perform(post("/internal/stats").contentType(MediaType.APPLICATION_JSON).content(new byte[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(BASE + "invalid_parameter"));
    }

    @Test
    @DisplayName("a servlet filter's problem carries the configured type base")
    void filterProblem() throws Exception {
        mockMvc.perform(post("/external/stats").contentType(MediaType.APPLICATION_JSON).content(new byte[300 * 1024]))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.type").value(BASE + "payload_too_large"));
    }

    @Test
    @DisplayName("an unconfirmed publish is 503 with the configured Retry-After and type base")
    void unavailable() throws Exception {
        when(publisher.publish(any(), any())).thenReturn(PublishOutcome.NOT_CONFIRMED);
        mockMvc.perform(post("/internal/stats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture("valid/lorastats-production-canonical.json")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "9"))
                .andExpect(jsonPath("$.type").value(BASE + "service_unavailable"));
    }

    private static byte[] fixture(String path) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                ProblemResponseSettingsIntegrationTest.class.getClassLoader().getResourceAsStream(path), path)) {
            return in.readAllBytes();
        }
    }
}
