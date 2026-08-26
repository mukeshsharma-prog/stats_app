package com.zenner.datahub.stats.common.model;

import static java.time.ZoneOffset.UTC;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TimestampsTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void readsAndWritesUnixSecondsWithoutInterpretingThemAsMilliseconds() throws Exception {
        JsonNode source = OBJECT_MAPPER.readTree("""
                {"processor": 1689338210, "device": 1689338200}
                """);

        Timestamps timestamps = OBJECT_MAPPER.treeToValue(source, Timestamps.class);

        assertThat(timestamps.device().atZone(UTC).getYear()).isEqualTo(2023);
        assertThat(timestamps.device().getEpochSecond()).isEqualTo(1689338200L);

        JsonNode serialized = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsBytes(timestamps));
        assertThat(serialized).isEqualTo(source);
        assertThat(serialized.path("device").isIntegralNumber()).isTrue();
    }
}
