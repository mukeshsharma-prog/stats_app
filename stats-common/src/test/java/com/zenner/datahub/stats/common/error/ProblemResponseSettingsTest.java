package com.zenner.datahub.stats.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProblemResponseSettingsTest {

    private static final ProblemDetails UNAVAILABLE =
            ProblemDetails.of(ErrorCode.SERVICE_UNAVAILABLE, "Retry.", "/internal/stats");

    @Test
    @DisplayName("a configured mapper writes the type under the deployment's base, wire code intact")
    void aConfiguredMapperRebasesTheType() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ProblemResponseSettings.of("https://errors.zenner.test/stats/", Duration.ofSeconds(5)).applyTo(mapper);

        JsonNode body = mapper.readTree(mapper.writeValueAsString(UNAVAILABLE));

        assertThat(body.get("type").asText()).isEqualTo("https://errors.zenner.test/stats/service_unavailable");
        // The record itself is unchanged; only what goes on the wire follows the deployment.
        assertThat(UNAVAILABLE.type()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.typeUri());
    }

    @Test
    @DisplayName("a mapper without settings writes the catalogue's own URI")
    void anUnconfiguredMapperWritesTheCatalogueUri() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(mapper.writeValueAsString(UNAVAILABLE));
        assertThat(body.get("type").asText()).isEqualTo(ErrorCode.TYPE_URI_BASE + "/service_unavailable");
    }

    @Test
    @DisplayName("applying the settings keeps attributes the mapper already carries")
    void applyingKeepsExistingAttributes() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setDefaultAttributes(
                mapper.getSerializationConfig().getAttributes().withSharedAttribute("other", "kept"));
        ProblemResponseSettings.of("https://errors.zenner.test", Duration.ofSeconds(5)).applyTo(mapper);
        assertThat(mapper.getSerializationConfig().getAttributes().getAttribute("other")).isEqualTo("kept");
    }

    @Test
    @DisplayName("Retry-After is delay-seconds and the type URI ends with the wire code")
    void headerAndTypeUri() {
        ProblemResponseSettings settings = ProblemResponseSettings.of("https://errors.zenner.test/", Duration.ofSeconds(7));
        assertThat(settings.retryAfterHeader()).isEqualTo("7");
        assertThat(settings.typeUri(ErrorCode.FORBIDDEN)).hasToString("https://errors.zenner.test/forbidden");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "errors/relative", "ftp://errors.zenner.test", "https://errors.zenner.test?x=1",
        "https://errors.zenner.test#top", "not a uri"})
    @DisplayName("a blank or unusable base stops startup, naming the key")
    void anUnusableBaseIsRefused(String base) {
        assertThatThrownBy(() -> ProblemResponseSettings.of(base, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ProblemResponseSettings.TYPE_URI_BASE_KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT-1S", "PT0.5S", "PT1.5S"})
    @DisplayName("Retry-After must be whole seconds, at least one")
    void anUnusableRetryAfterIsRefused(String retryAfter) {
        assertThatThrownBy(() -> ProblemResponseSettings.of("https://errors.zenner.test", Duration.parse(retryAfter)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ProblemResponseSettings.RETRY_AFTER_KEY);
    }
}
