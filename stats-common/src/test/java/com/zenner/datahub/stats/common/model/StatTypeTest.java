package com.zenner.datahub.stats.common.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StatTypeTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({
        "LORASTATS, lorastats",
        "LTESTATS, ltestats",
        "SYSSTATS, sysstats",
        "NETSTATS, netstats",
        "OMSSTATS, omsstats"
    })
    void ownsItsExactWireName(StatType type, String wireName) throws Exception {
        assertThat(type.wireName()).isEqualTo(wireName);
        assertThat(StatType.fromWireName(wireName)).isSameAs(type);
        assertThat(OBJECT_MAPPER.writeValueAsString(type)).isEqualTo('"' + wireName + '"');
    }

    @Test
    void rejectsUnknownAndCaseChangedWireNames() {
        assertThatThrownBy(() -> StatType.fromWireName("futurestats"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown stat type literal")
                .hasMessageNotContaining("futurestats");

        assertThatThrownBy(() -> StatType.fromWireName("LORASTATS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown stat type literal");
    }

    @Test
    void unknownTypeFixtureFailsDuringEnvelopeDeserialization() throws IOException {
        JsonNode fixture = readFixture("invalid/envelope-unknown-type.json");

        assertThatThrownBy(() -> OBJECT_MAPPER.treeToValue(fixture, StatsEnvelope.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessage("Unknown stat type literal")
                .hasMessageNotContaining(fixture.path("type").asText());
    }

    private static JsonNode readFixture(String name) throws IOException {
        try (InputStream input = StatTypeTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(input).as("fixture %s", name).isNotNull();
            return OBJECT_MAPPER.readTree(input);
        }
    }
}
