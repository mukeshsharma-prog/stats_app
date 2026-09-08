package com.zenner.datahub.stats.input.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.input.ingest.IngestMetrics;
import com.zenner.datahub.stats.input.publish.PublishOutcome;
import com.zenner.datahub.stats.input.publish.StatsPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * The HTTP contract of the ingest endpoint (heimdal-input-contract-v1.md §2).
 *
 * <p>The publisher is stubbed because publisher-confirm semantics are T-014. What is
 * asserted here is the boundary this task owns: which conditions produce a 400, that
 * a rejected record is never handed to the publisher, and that a record that is not
 * confirmed produces a 503 rather than a success.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            // T-017 deliberately gives these no default in code, so a deployment that
            // forgets them fails to start. Tests must supply them like any deployment.
            "STATS_INPUT_AUTH_MODE=none",
            "STATS_INGEST_ROLE=stats.ingest"
        })
class StatsIngestControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private MeterRegistry meterRegistry;
    @MockitoBean private StatsPublisher publisher;

    @Test
    @DisplayName("a confirmed publish returns the configured success status")
    void confirmedPublishSucceeds() throws Exception {
        when(publisher.publish(eq(StatType.LORASTATS), any())).thenReturn(PublishOutcome.CONFIRMED);
        double acceptedBefore = counter(IngestMetrics.ACCEPTED);
        double converterBefore = counter(IngestMetrics.CONVERTER_TS_PRESENT);

        mockMvc.perform(postRecord("valid/lorastats-production-canonical.json"))
                .andExpect(status().isOk());

        assertThat(counter(IngestMetrics.ACCEPTED) - acceptedBefore).isEqualTo(1.0d);
        // ADR-007 §2 — accepted, never read, metered (T-059).
        assertThat(counter(IngestMetrics.CONVERTER_TS_PRESENT) - converterBefore).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("an unconfirmed publish is 503, never a success — rule 8")
    void unconfirmedPublishIsUnavailable() throws Exception {
        when(publisher.publish(eq(StatType.LORASTATS), any()))
                .thenReturn(PublishOutcome.NOT_CONFIRMED);
        double before = counter(IngestMetrics.PUBLISH_FAILED);

        mockMvc.perform(postRecord("valid/lorastats-production-canonical.json"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        assertThat(counter(IngestMetrics.PUBLISH_FAILED) - before).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("an unknown-dialect record is ACCEPTED and published — ADR-007 §5")
    void publishesAPayloadDialectMismatch() throws Exception {
        when(publisher.publish(eq(StatType.LORASTATS), any())).thenReturn(PublishOutcome.CONFIRMED);

        mockMvc.perform(postRecord("invalid/lorastats-dialect-unknown.json"))
                .andExpect(status().isOk());

        verify(publisher).publish(eq(StatType.LORASTATS), any());
    }

    @Test
    @DisplayName("an envelope failure is 400 and NOTHING is published — rule 7")
    void envelopeFailurePublishesNothing() throws Exception {
        mockMvc.perform(
                        post("/stats")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"type\":\"lorastats\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(publisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("clock skew is 400 with clock_skew_exceeded and publishes nothing")
    void clockSkewIsRejected() throws Exception {
        double before = counter(IngestMetrics.CLOCK_SKEW_REJECTED);

        mockMvc.perform(postRecord("edge/lorastats-clock-skew-future.json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("clock_skew_exceeded"))
                .andExpect(jsonPath("$.errors[0].field").value("/ts/device"));

        verify(publisher, never()).publish(any(), any());
        assertThat(counter(IngestMetrics.CLOCK_SKEW_REJECTED) - before).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("an empty body is 400, not a 500")
    void emptyBodyIsRejected() throws Exception {
        mockMvc.perform(post("/stats").contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest());

        verify(publisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("non-POST methods are 405 with no side effect — the API is write-once here")
    void nonPostMethodsAreRejected() throws Exception {
        mockMvc.perform(get("/stats")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put("/stats").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());

        verify(publisher, never()).publish(any(), any());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postRecord(
            String fixture) {
        return post("/stats").contentType(MediaType.APPLICATION_JSON).content(fixture(fixture));
    }

    /**
     * Counters live in the cached application context and survive between tests, so
     * every assertion here is a DELTA. An absolute count would pass or fail depending
     * on JUnit's method order, which is exactly the kind of test that gets deleted
     * later for being flaky rather than fixed.
     */
    private double counter(String name) {
        try {
            return meterRegistry.get(name).counter().count();
        } catch (MeterNotFoundException absent) {
            return 0.0d;
        }
    }

    private static byte[] fixture(String name) {
        try (InputStream in =
                StatsIngestControllerTest.class.getClassLoader().getResourceAsStream(name)) {
            return Objects.requireNonNull(in, "fixture not on the test classpath: " + name)
                    .readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("could not read fixture " + name, e);
        }
    }
}
