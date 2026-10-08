package com.zenner.datahub.stats.input.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zenner.datahub.stats.input.publish.PublishOutcome;
import com.zenner.datahub.stats.input.publish.StatsPublisher;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The alternate configured success status keeps the same acknowledgement on both doors. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "STATS_INPUT_AUTH_MODE=none",
    "STATS_INGEST_ROLE=stats.ingest",
    "stats.input.endpoint.success-status=202"
})
class StatsIngestAcceptedStatusTest {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private StatsPublisher publisher;

    @Test
    void confirmedPublishReturns202WithAcknowledgementOnBothDoors() throws Exception {
        when(publisher.publish(any(), any())).thenReturn(PublishOutcome.CONFIRMED);
        try (var stream = Objects.requireNonNull(getClass().getClassLoader()
                .getResourceAsStream("valid/lorastats-production-canonical.json"))) {
            byte[] body = stream.readAllBytes();
            for (String door : List.of("/internal/stats", "/external/stats")) {
                mockMvc.perform(post(door).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isAccepted())
                        .andExpect(StatsIngestControllerTest.acceptedAcknowledgement());
            }
        }
    }
}
