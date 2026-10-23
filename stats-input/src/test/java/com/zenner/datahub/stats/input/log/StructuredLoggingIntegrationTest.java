package com.zenner.datahub.stats.input.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.input.publish.PublishOutcome;
import com.zenner.datahub.stats.input.publish.StatsPublisher;
import java.io.InputStream;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
        properties = {
            "STATS_INPUT_AUTH_MODE=none",
            "STATS_INGEST_ROLE=stats.ingest",
            "logging.level.com.zenner.datahub.stats.input.web.StatsIngestController=DEBUG"
        })
class StructuredLoggingIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private StatsPublisher publisher;

    @Test
    void confirmedRecordHasTheContractedFieldsAndNoPayload(CapturedOutput output) throws Exception {
        when(publisher.publish(eq(StatType.LORASTATS), any())).thenReturn(PublishOutcome.CONFIRMED);

        mockMvc.perform(
                        post("/internal/stats")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer must-never-reach-a-log")
                                .content(fixture()))
                .andExpect(status().isOk())
                .andExpect(header().exists(IngestLoggingFilter.TRACE_ID_RESPONSE_HEADER));

        String line = eventLine(output, "published");
        JsonNode event = objectMapper.readTree(line);
        assertThat(event.path("module").asText()).isEqualTo("input");
        assertThat(event.path("event").asText()).isEqualTo("published");
        assertThat(event.path("traceId").asText()).isNotBlank();
        assertThat(event.path("uid").asText())
                .isEqualTo("CLSCON-11d3ec4c-f6e1-403a-a9ef-b6f5fe909eee");
        assertThat(event.path("statType").asText()).isEqualTo("lorastats");
        assertThat(event.path("tenant").asText()).isEqualTo("mzc");
        assertThat(event.path("clsbox").asText()).isEqualTo("04B64810652B");
        assertThat(event.path("errorCode").asText()).isEqualTo("none");
        // Which door, on every line of the request.
        assertThat(event.path("ingestSource").asText()).isEqualTo("internal");

        assertThat(line)
                .doesNotContain("rx_nr_pkts")
                .doesNotContain("tx_success")
                .doesNotContain("must-never-reach-a-log")
                .doesNotContain("Authorization");
    }

    @Test
    void malformedBodyUsesSafePlaceholdersAndDoesNotEchoInput(CapturedOutput output)
            throws Exception {
        mockMvc.perform(
                        post("/internal/stats")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"secret\":\"must-not-be-echoed\","))
                .andExpect(status().isBadRequest());

        String line = eventLine(output, "validation_failed");
        JsonNode event = objectMapper.readTree(line);
        assertThat(event.path("module").asText()).isEqualTo("input");
        assertThat(event.path("traceId").asText()).isNotBlank();
        assertThat(event.path("uid").asText()).isEqualTo("unknown");
        assertThat(event.path("statType").asText()).isEqualTo("unknown");
        assertThat(event.path("tenant").asText()).isEqualTo("unknown");
        assertThat(event.path("clsbox").asText()).isEqualTo("unknown");
        assertThat(event.path("errorCode").asText()).isEqualTo("invalid_parameter");
        assertThat(event.path("ingestSource").asText()).isEqualTo("internal");
        assertThat(line).doesNotContain("must-not-be-echoed");
    }

    private String eventLine(CapturedOutput output, String event) {
        return output.getOut().lines()
                .filter(line -> line.contains("\"event\":\"" + event + "\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No structured event " + event + " in output"));
    }

    private static byte[] fixture() throws Exception {
        try (InputStream input = StructuredLoggingIntegrationTest.class
                .getClassLoader()
                .getResourceAsStream("valid/lorastats-production-canonical.json")) {
            return Objects.requireNonNull(input, "fixture missing").readAllBytes();
        }
    }
}
