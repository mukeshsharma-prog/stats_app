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
import java.util.List;
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
        double cycleSamplesBefore = cycleOffsetSamples(StatType.LORASTATS);

        mockMvc.perform(postRecord("valid/lorastats-production-canonical.json"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        assertThat(counter(IngestMetrics.PUBLISH_FAILED) - before).isEqualTo(1.0d);
        // T-050: the cycle histogram counts ACCEPTED records only. Heimdal resends after a
        // 503, and counting the attempt here would count that record twice.
        assertThat(cycleOffsetSamples(StatType.LORASTATS)).isEqualTo(cycleSamplesBefore);
    }

    /**
     * T-050 / DL-147: the two envelope observations metered on acceptance — where in the
     * ten-minute cycle {@code ts.server} fell, and whether Heimdal corrected
     * {@code ts.device} (DL-146). Both are read to be counted and for nothing else.
     */
    @Test
    @DisplayName("an accepted record is placed in the reporting-cycle histogram, and a corrected one is counted")
    void metersCyclePhaseAndTsDeviceCorrectionOnAcceptance() throws Exception {
        when(publisher.publish(eq(StatType.SYSSTATS), any())).thenReturn(PublishOutcome.CONFIRMED);
        double samplesBefore = cycleOffsetSamples(StatType.SYSSTATS);
        double correctedBefore = counter(IngestMetrics.TS_DEVICE_CORRECTED);

        mockMvc.perform(postRecord("valid/sysstats-production-ts-device-corrected.json"))
                .andExpect(status().isOk());

        assertThat(cycleOffsetSamples(StatType.SYSSTATS) - samplesBefore).isEqualTo(1.0d);
        // ts.server 1786617001 mod 600 = 1.
        assertThat(
                        meterRegistry
                                .get(IngestMetrics.TS_SERVER_CYCLE_OFFSET)
                                .tag("type", "sysstats")
                                .summary()
                                .max())
                .isLessThanOrEqualTo(599.0d);
        assertThat(counter(IngestMetrics.TS_DEVICE_CORRECTED) - correctedBefore).isEqualTo(1.0d);
    }

    private double cycleOffsetSamples(StatType type) {
        try {
            return meterRegistry
                    .get(IngestMetrics.TS_SERVER_CYCLE_OFFSET)
                    .tag("type", type.wireName())
                    .summary()
                    .count();
        } catch (MeterNotFoundException absent) {
            return 0.0d;
        }
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
                        post("/internal/stats")
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
        mockMvc.perform(post("/internal/stats").contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest());

        verify(publisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("non-POST methods are 405 with no side effect on either door — the API is write-once here")
    void nonPostMethodsAreRejected() throws Exception {
        for (String door : List.of("/internal/stats", "/external/stats")) {
            mockMvc.perform(get(door)).andExpect(status().isMethodNotAllowed());
            mockMvc.perform(put(door).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isMethodNotAllowed());
        }

        verify(publisher, never()).publish(any(), any());
    }

    /**
     * DL-151: two doors, one behaviour. The external path accepts exactly what the
     * internal one accepts, rejects exactly what it rejects, and the only observable
     * difference is the {@code source} label on the counters.
     */
    @Test
    @DisplayName("the external door runs the same gate and publisher, and is labelled source=external")
    void externalDoorBehavesIdenticallyAndIsLabelled() throws Exception {
        when(publisher.publish(eq(StatType.LORASTATS), any())).thenReturn(PublishOutcome.CONFIRMED);
        double externalAcceptedBefore = counter(IngestMetrics.ACCEPTED, "external");
        double internalAcceptedBefore = counter(IngestMetrics.ACCEPTED, "internal");
        double externalRejectedBefore = counter(IngestMetrics.VALIDATION_FAILED, "external");

        mockMvc.perform(
                        post("/external/stats")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(fixture("valid/lorastats-production-canonical.json")))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/external/stats")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"type\":\"lorastats\"}"))
                .andExpect(status().isBadRequest());

        assertThat(counter(IngestMetrics.ACCEPTED, "external") - externalAcceptedBefore).isEqualTo(1.0d);
        assertThat(counter(IngestMetrics.ACCEPTED, "internal") - internalAcceptedBefore).isZero();
        assertThat(counter(IngestMetrics.VALIDATION_FAILED, "external") - externalRejectedBefore)
                .isEqualTo(1.0d);
        verify(publisher).publish(eq(StatType.LORASTATS), any());
    }

    @Test
    @DisplayName("the internal door is labelled source=internal")
    void internalDoorIsLabelled() throws Exception {
        when(publisher.publish(eq(StatType.LORASTATS), any())).thenReturn(PublishOutcome.CONFIRMED);
        double before = counter(IngestMetrics.ACCEPTED, "internal");

        mockMvc.perform(postRecord("valid/lorastats-production-canonical.json"))
                .andExpect(status().isOk());

        assertThat(counter(IngestMetrics.ACCEPTED, "internal") - before).isEqualTo(1.0d);
    }

    /** A path that is neither door is not an ingest endpoint at all. */
    @Test
    @DisplayName("the old single path no longer exists")
    void legacySinglePathIsGone() throws Exception {
        mockMvc.perform(
                        post("/stats")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(fixture("valid/lorastats-production-canonical.json")))
                .andExpect(status().isNotFound());

        verify(publisher, never()).publish(any(), any());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postRecord(
            String fixture) {
        return post("/internal/stats").contentType(MediaType.APPLICATION_JSON).content(fixture(fixture));
    }

    /**
     * Counters live in the cached application context and survive between tests, so
     * every assertion here is a DELTA. An absolute count would pass or fail depending
     * on JUnit's method order, which is exactly the kind of test that gets deleted
     * later for being flaky rather than fixed.
     */
    private double counter(String name) {
        // Summed across every type and source label: since T-050 this class posts sysstats
        // records too, so a name can match several counters and "the first one" depends
        // on which test ran first. A delta over the sum does not.
        try {
            return meterRegistry.get(name).counters().stream()
                    .mapToDouble(io.micrometer.core.instrument.Counter::count)
                    .sum();
        } catch (MeterNotFoundException absent) {
            return 0.0d;
        }
    }

    /** The same delta, restricted to one ingest door (DL-151). */
    private double counter(String name, String source) {
        try {
            return meterRegistry.get(name).tag("source", source).counters().stream()
                    .mapToDouble(io.micrometer.core.instrument.Counter::count)
                    .sum();
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
