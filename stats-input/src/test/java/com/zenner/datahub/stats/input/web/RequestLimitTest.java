package com.zenner.datahub.stats.input.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zenner.datahub.stats.common.error.ErrorCode;
import com.zenner.datahub.stats.common.error.ProblemDetails;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.input.StatsInputApplication;
import com.zenner.datahub.stats.input.ingest.IngestMetrics;
import com.zenner.datahub.stats.input.publish.PublishOutcome;
import com.zenner.datahub.stats.input.publish.StatsPublisher;
import io.micrometer.core.instrument.MeterRegistry;
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
 * The request bounds of T-018, at values small enough to hit deliberately.
 *
 * <p>Both limits are set here to numbers no deployment would use, which is the point:
 * a limit tested at its production value is a limit tested only by hoping. The publisher
 * is a mock because neither bound may reach it — a rejected request is rejected before
 * anything is published (rule 7).</p>
 */
@SpringBootTest(classes = StatsInputApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "STATS_INPUT_AUTH_MODE=none",
            "STATS_INGEST_ROLE=stats.ingest",
            "stats.input.limits.max-request-bytes=1KB",
            "stats.input.limits.max-requests-per-second=1"
        })
class RequestLimitTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private MeterRegistry meterRegistry;
    @MockitoBean private StatsPublisher publisher;

    /**
     * 1 KB is chosen so the canonical production record (1,758 bytes) is over the line
     * and the smaller adversarial fixture (429 bytes) is under it. Both bounds are then
     * exercised in one context without the size filter masking the rate limit — it runs
     * first, by order, and a 413 never reaches the bucket.
     */
    @Test
    @DisplayName("an oversized body is 413 problem+json, and nothing is read or published")
    void oversizedBodyIsRejectedWith413() throws Exception {
        mockMvc.perform(postRecord("valid/lorastats-production-canonical.json"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(ProblemDetails.CONTENT_TYPE))
                .andExpect(
                        jsonPath("$.type").value(ErrorCode.PAYLOAD_TOO_LARGE.typeUri().toString()));

        verify(publisher, never()).publish(any(StatType.class), any(byte[].class));
        assertThat(meterRegistry.counter(IngestMetrics.REQUEST_TOO_LARGE, "source", "internal").count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("past the rate limit the answer is 429 with Retry-After, not a dropped record")
    void excessRequestsAreRateLimited() throws Exception {
        when(publisher.publish(any(StatType.class), any(byte[].class)))
                .thenReturn(PublishOutcome.CONFIRMED);

        // One token per second, one second of burst: the first request spends it.
        mockMvc.perform(postRecord("byte-identity/lorastats-adversarial-formatting.json"))
                .andExpect(status().isOk());

        mockMvc.perform(postRecord("byte-identity/lorastats-adversarial-formatting.json"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(ProblemDetails.CONTENT_TYPE))
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(
                        jsonPath("$.type").value(ErrorCode.TOO_MANY_REQUESTS.typeUri().toString()));

        assertThat(meterRegistry.counter(IngestMetrics.RATE_LIMITED, "source", "internal").count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("neither limit applies to the readiness probe")
    void limitsDoNotCoverTheHealthEndpoint() throws Exception {
        // A rate limit that covers the health endpoint lets ingest load fail the
        // container health check and take the instance out of rotation — an incident
        // manufactured by the defence against one (D13, D-10).
        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                    "/actuator/health/liveness"))
                    .andExpect(status().isOk());
        }
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            postRecord(String fixture) {
        return post("/internal/stats").contentType(MediaType.APPLICATION_JSON).content(fixture(fixture));
    }

    private static byte[] fixture(String name) {
        try (InputStream in = RequestLimitTest.class.getClassLoader().getResourceAsStream(name)) {
            return Objects.requireNonNull(in, "fixture not on the test classpath: " + name)
                    .readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("could not read fixture " + name, e);
        }
    }
}
