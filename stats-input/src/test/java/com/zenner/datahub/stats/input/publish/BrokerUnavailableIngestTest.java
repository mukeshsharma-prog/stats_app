package com.zenner.datahub.stats.input.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zenner.datahub.stats.common.error.ErrorCode;
import com.zenner.datahub.stats.common.error.ProblemDetails;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.input.StatsInputApplication;
import com.zenner.datahub.stats.input.ingest.IngestMetrics;
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
import org.springframework.test.web.servlet.MockMvc;

/**
 * Broker unavailable ⇒ 503, with no local buffering.
 *
 * <p>Input is stateless by decision. The temptation in this situation is to hold the
 * record and publish it when the broker returns, which trades a retry Heimdal already
 * owns for a queue in a process that can be killed at any moment. The 503 is the
 * correct answer and the honest one.</p>
 *
 * <p>The context points at a closed port rather than stopping the shared container,
 * which is a JVM singleton other tests are using.</p>
 */
@SpringBootTest(classes = StatsInputApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "STATS_INPUT_AUTH_MODE=none",
            "STATS_INGEST_ROLE=stats.ingest",
            "spring.rabbitmq.host=127.0.0.1",
            "spring.rabbitmq.port=1",
            "stats.amqp.confirm-timeout=2s"
        })
class BrokerUnavailableIngestTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private MeterRegistry meterRegistry;

    @Test
    @DisplayName("a valid record with no broker is 503 problem+json, and is metered")
    void validRecordWithNoBrokerIsServiceUnavailable() throws Exception {
        mockMvc.perform(
                        post("/internal/stats")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(fixture("valid/lorastats-production-canonical.json")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(ProblemDetails.CONTENT_TYPE))
                .andExpect(jsonPath("$.type").value(ErrorCode.SERVICE_UNAVAILABLE.typeUri().toString()))
                .andExpect(jsonPath("$.statType").value(StatType.LORASTATS.wireName()));

        assertThat(
                        meterRegistry
                                .counter(
                                        IngestMetrics.PUBLISH_FAILED,
                                        "type",
                                        StatType.LORASTATS.wireName(),
                                        "source",
                                        "internal")
                                .count())
                .isEqualTo(1.0d);
        // Nothing was returned, because nothing was ever published. An unroutable
        // counter that moved here would mean we were labelling an outage as a topology
        // fault, and the runbook entries for the two are different.
        // The series exists from startup; what must not have happened is a move.
        assertThat(meterRegistry.find(IngestMetrics.UNROUTABLE).counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    private static byte[] fixture(String name) {
        try (InputStream in =
                BrokerUnavailableIngestTest.class.getClassLoader().getResourceAsStream(name)) {
            return Objects.requireNonNull(in, "fixture not on the test classpath: " + name)
                    .readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("could not read fixture " + name, e);
        }
    }
}
