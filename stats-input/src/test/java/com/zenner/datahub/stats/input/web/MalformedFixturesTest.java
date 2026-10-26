package com.zenner.datahub.stats.input.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.zenner.datahub.stats.input.publish.PublishOutcome;
import com.zenner.datahub.stats.input.publish.StatsPublisher;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Every file in {@code docs/schemas/fixtures/malformed} over HTTP, with the outcome its
 * README promises. The parser-level files are refused before anything is published; the
 * two whose stress sits inside {@code data} are well-formed envelopes, so Input accepts
 * them and the Writer's payload gate dead-letters them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"STATS_INPUT_AUTH_MODE=none", "STATS_INGEST_ROLE=stats.ingest"})
class MalformedFixturesTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private StatsPublisher publisher;

    @BeforeEach
    void confirmEveryPublish() {
        clearInvocations(publisher);
        when(publisher.publish(any(), any())).thenReturn(PublishOutcome.CONFIRMED);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "truncated-body.json",
        "not-json.txt",
        "form-encoded.txt",
        "top-level-array.json",
        "json-null.json",
        "duplicate-keys.json",
        "invalid-utf8.json",
        "trailing-content.json"
    })
    @DisplayName("a parser-level fixture sent as JSON is a 400 problem and nothing is published")
    void refused(String file) throws Exception {
        MockHttpServletResponse response = send(fixture("malformed/" + file), MediaType.APPLICATION_JSON_VALUE);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).doesNotContain("Exception", "at com.", "jackson");
        verify(publisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("a form-encoded body is 415 and nothing is published")
    void formEncodedIsUnsupported() throws Exception {
        MockHttpServletResponse response =
                send(fixture("malformed/form-encoded.txt"), MediaType.APPLICATION_FORM_URLENCODED_VALUE);

        assertThat(response.getStatus()).isEqualTo(415);
        // Spring's own 415, before the controller: no problem body yet (the catalogue has
        // no unsupported_media_type code). Pinned so adding one is a visible change.
        assertThat(response.getContentAsString()).isEmpty();
        verify(publisher, never()).publish(any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"deep-nesting.json", "oversized-string.json"})
    @DisplayName("a well-formed envelope whose stress is inside data is accepted; data is the Writer's gate")
    void acceptedForTheWriterToJudge(String file) throws Exception {
        MockHttpServletResponse response = send(fixture("malformed/" + file), MediaType.APPLICATION_JSON_VALUE);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(publisher, times(1)).publish(any(), any());
    }

    @Test
    @DisplayName("nesting past the parser's depth limit is 400 malformed_json, never a StackOverflowError")
    void nestingPastTheParserLimitIsRefused() throws Exception {
        long now = Instant.now().getEpochSecond();
        String deep = "[".repeat(5_000) + "]".repeat(5_000);
        String body = """
                {"uid":"too-deep","tenant":"tenant-alpha","maintenant":"management-parent",
                 "clsbox":"A1B2C3D4E5F6","type":"sysstats","ts":{"server":%d,"device":%d},
                 "data":{"deep":%s}}""".formatted(now, now, deep);

        MockHttpServletResponse response =
                send(body.getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON_VALUE);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.getContentAsString(), "$.errors[0].code"))
                .isEqualTo("malformed_json");
        verify(publisher, never()).publish(any(), any());
    }

    private MockHttpServletResponse send(byte[] body, String contentType) throws Exception {
        return mockMvc.perform(post("/internal/stats").contentType(contentType).content(body))
                .andReturn()
                .getResponse();
    }

    private static byte[] fixture(String path) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                MalformedFixturesTest.class.getClassLoader().getResourceAsStream(path), path)) {
            return in.readAllBytes();
        }
    }
}
