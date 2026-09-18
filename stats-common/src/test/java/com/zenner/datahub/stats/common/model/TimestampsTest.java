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
        assertThat(timestamps.expiresOn().asText()).isEqualTo("2026-08-27T12:29:57.182Z");
        assertThat(OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsBytes(timestamps)))
                .isEqualTo(source);
    }

    @Test
    void keepsExpiresOnInExtendedJsonFormAndDeviceOrigWhenHeimdalSendsThem() throws Exception {
        // Live records on 18 Sep 2026 (DL-146): expiresOn as Mongo Extended JSON rather
        // than a plain string, and deviceOrig holding the gateway's own clock after
        // Heimdal rewrote device. Both must survive the typed view untouched - a String
        // field would have thrown on the first, and ignoreUnknown would have silently
        // dropped the second.
        JsonNode source = OBJECT_MAPPER.readTree("""
                {"server": 1786617001, "datastore": 1786617001, "converter": 1786617001,
                 "deviceOrig": 1786616989, "device": 1786617001,
                 "expiresOn": {"$date": "2026-08-13T10:30:01.3Z"}}
                """);

        Timestamps timestamps = OBJECT_MAPPER.treeToValue(source, Timestamps.class);

        assertThat(timestamps.deviceOrig().getEpochSecond()).isEqualTo(1786616989L);
        assertThat(timestamps.device().getEpochSecond()).isEqualTo(1786617001L);
        assertThat(timestamps.expiresOn().isObject()).isTrue();
        assertThat(timestamps.expiresOn().path("$date").asText()).isEqualTo("2026-08-13T10:30:01.3Z");
        assertThat(OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsBytes(timestamps)))
                .isEqualTo(source);
    }
}
