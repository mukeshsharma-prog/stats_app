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
                {"server": 1689338210, "device": 1689338200}
                """);

        Timestamps timestamps = OBJECT_MAPPER.treeToValue(source, Timestamps.class);

        assertThat(timestamps.device().atZone(UTC).getYear()).isEqualTo(2023);
        assertThat(timestamps.device().getEpochSecond()).isEqualTo(1689338200L);

        JsonNode serialized = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsBytes(timestamps));
        assertThat(serialized).isEqualTo(source);
        assertThat(serialized.path("device").isIntegralNumber()).isTrue();
    }

    @Test
    void readsTheProductionBlockWhereExpiresOnIsADateStringNotAnEpochInteger() throws Exception {
        // Every production ts carries five members, and expiresOn is the odd one out:
        // an ISO-8601 string sitting among four epoch integers. Typing it as an epoch
        // would throw on every record Heimdal sends.
        JsonNode source = OBJECT_MAPPER.readTree("""
                {"server": 1787833797, "datastore": 1787833797, "converter": 1787833797,
                 "device": 1787833797, "expiresOn": "2026-08-27T12:29:57.182Z"}
                """);

        Timestamps timestamps = OBJECT_MAPPER.treeToValue(source, Timestamps.class);

        assertThat(timestamps.server().getEpochSecond()).isEqualTo(1787833797L);
        assertThat(timestamps.converter().getEpochSecond()).isEqualTo(1787833797L);
        assertThat(timestamps.expiresOn()).isEqualTo("2026-08-27T12:29:57.182Z");
        assertThat(OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsBytes(timestamps)))
                .isEqualTo(source);
    }
}
