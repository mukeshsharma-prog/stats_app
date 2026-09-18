package com.zenner.datahub.stats.writer.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Keeps the sysstats writer's field mapping in step with the normative schema — the
 * same guard {@code LorastatsColumnMappingTest} gives the reference slice. A member the
 * schema names and the writer does not would land in {@code data_extra} with no error
 * and a column that is always null.
 */
class SysstatsColumnMappingTest {

    private static final String SCHEMA_RESOURCE = "schemas/sysstats-v1.schema.json";

    @Test
    void mapsExactlyTheDataMembersTheSchemaNames() {
        assertThat(SysstatsBatchWriter.MAPPED_DATA_FIELDS)
                .containsExactlyInAnyOrderElementsOf(schemaDataFields());
    }

    @Test
    void requiredAndOptionalListsMatchTheSchemasRequiredArray() {
        JsonNode data = schemaData();
        List<String> required = new ArrayList<>();
        data.path("required").forEach(name -> required.add(name.asText()));

        assertThat(SysstatsBatchWriter.REQUIRED_FIELDS).containsExactlyInAnyOrderElementsOf(required);
        assertThat(SysstatsBatchWriter.OPTIONAL_FIELDS).doesNotContainAnyElementsOf(required);
        // The one wire-type trap this type carries (ADR-007 §6): timespan is a STRING here.
        assertThat(data.path("properties").path("timespan").path("type").asText()).isEqualTo("string");
        assertThat(SysstatsBatchWriter.OPTIONAL_FIELDS).contains("timespan");
    }

    @Test
    void theInsertStatementNamesEveryMappedFieldAndTheAdr003EnvelopeColumns() {
        String insertSql =
                (String) ReflectionTestUtils.getField(SysstatsBatchWriter.class, "INSERT_SQL");

        assertThat(insertSql).isNotNull().startsWith("INSERT INTO sysstats");
        for (String field : SysstatsBatchWriter.MAPPED_DATA_FIELDS) {
            assertThat(insertSql).as("column for data.%s", field).contains(field);
        }
        assertThat(insertSql)
                .contains("record_version")
                .contains("schema_version")
                .contains("passthrough")
                .contains("data_extra")
                .contains("ON CONFLICT (ts_device, uid) DO NOTHING");
    }

    private static Set<String> schemaDataFields() {
        List<String> names = new ArrayList<>();
        schemaData().path("properties").fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    private static JsonNode schemaData() {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream input =
                SysstatsColumnMappingTest.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
            assertThat(input).as("bundled schema %s", SCHEMA_RESOURCE).isNotNull();
            JsonNode schema = objectMapper.readTree(input);
            for (JsonNode branch : schema.path("allOf")) {
                JsonNode data = branch.path("properties").path("data");
                if (data.isObject()) {
                    assertThat(data.path("properties").isObject())
                            .as("sysstats data declares its properties")
                            .isTrue();
                    return data;
                }
            }
            throw new AssertionError("sysstats schema declares no data properties");
        } catch (java.io.IOException unreadable) {
            throw new AssertionError("Unable to read " + SCHEMA_RESOURCE, unreadable);
        }
    }
}
