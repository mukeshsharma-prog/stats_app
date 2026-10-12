package com.zenner.datahub.stats.writer.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class LteNetstatsColumnMappingTest {
    @Test
    void lteMapsBothShapesAndTheCapitalizedWireKey() throws Exception {
        JsonNode schema = schema("ltestats");
        Set<String> properties = new HashSet<>();
        schema.at("/$defs/shapeMeasured/properties").fieldNames().forEachRemaining(properties::add);
        schema.at("/$defs/shapeUnknownTechnology/properties").fieldNames().forEachRemaining(properties::add);
        assertThat(LtestatsBatchWriter.MAPPED_DATA_FIELDS).containsExactlyInAnyOrderElementsOf(properties);
        Set<String> required = new HashSet<>();
        schema.at("/$defs/shapeMeasured/required").forEach(value -> required.add(value.asText()));
        assertThat(LtestatsBatchWriter.REQUIRED_FIELDS).containsExactlyInAnyOrderElementsOf(required);
        assertThat(sql(LtestatsBatchWriter.class)).contains("access_technology", ":etxt", "passthrough", "data_extra",
                "ON CONFLICT (ts_device, uid) DO NOTHING");
    }

    @Test
    void netChecksEverySchemaCounterOnDynamicInterfaces() throws Exception {
        JsonNode schema = schema("netstats");
        Set<String> fields = new HashSet<>();
        Set<String> counters = new HashSet<>();
        schema.at("/$defs/interface/properties").properties().forEach(entry -> {
            fields.add(entry.getKey());
            if (entry.getValue().path("$ref").asText().equals("#/$defs/counter")) counters.add(entry.getKey());
        });
        assertThat(NetstatsBatchWriter.INTERFACE_FIELDS).containsExactlyInAnyOrderElementsOf(fields);
        assertThat(NetstatsBatchWriter.COUNTER_FIELDS).containsExactlyInAnyOrderElementsOf(counters);
        assertThat(sql(NetstatsBatchWriter.class)).contains("interfaces", "CAST(:interfaces AS jsonb)", "data_extra",
                "ON CONFLICT (ts_device, uid) DO NOTHING");
    }

    private static String sql(Class<?> type) {
        return (String) ReflectionTestUtils.getField(type, "INSERT_SQL");
    }

    private static JsonNode schema(String type) throws Exception {
        try (var input = LteNetstatsColumnMappingTest.class.getResourceAsStream("/schemas/" + type + "-v1.schema.json")) {
            return new ObjectMapper().readTree(input);
        }
    }
}
