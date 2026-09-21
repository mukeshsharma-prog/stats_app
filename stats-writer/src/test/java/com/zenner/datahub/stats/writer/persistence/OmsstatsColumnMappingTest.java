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
 * Keeps the omsstats writer's field mapping in step with the normative schema — the
 * same guard {@code SysstatsColumnMappingTest} gives the reference slice. A member the
 * schema names and the writer does not would land in {@code data_extra} with no error
 * and a column that is always null.
 */
class OmsstatsColumnMappingTest {

    private static final String SCHEMA_RESOURCE = "schemas/omsstats-v1.schema.json";

    @Test
    void mapsExactlyTheDataMembersTheSchemaNames() {
        assertThat(OmsstatsBatchWriter.MAPPED_DATA_FIELDS)
                .containsExactlyInAnyOrderElementsOf(schemaDataFields());
    }

    @Test
    void requiredAndOptionalListsMatchTheSchemasRequiredArray() {
        JsonNode data = schemaData();
        List<String> required = new ArrayList<>();
        data.path("required").forEach(name -> required.add(name.asText()));

        assertThat(OmsstatsBatchWriter.REQUIRED_FIELDS).containsExactlyInAnyOrderElementsOf(required);
        assertThat(OmsstatsBatchWriter.OPTIONAL_FIELDS).doesNotContainAnyElementsOf(required).isEmpty();
        // No wire-type trap on this type (ADR-007 §6): every member, timespan included, is an
        // INTEGER -- the string "300" belongs to sysstats, and a copy that kept the digit-string
        // parser would refuse every production omsstats record.
        for (String field : OmsstatsBatchWriter.REQUIRED_FIELDS) {
            assertThat(data.path("properties").path(field).path("type").asText())
                    .as("wire type of data.%s", field)
                    .isEqualTo("integer");
        }
    }

    @Test
    void theInsertStatementNamesEveryMappedFieldAndTheAdr003EnvelopeColumns() {
        String insertSql =
                (String) ReflectionTestUtils.getField(OmsstatsBatchWriter.class, "INSERT_SQL");

        assertThat(insertSql).isNotNull().startsWith("INSERT INTO omsstats");
        for (String field : OmsstatsBatchWriter.MAPPED_DATA_FIELDS) {
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
                OmsstatsColumnMappingTest.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
            assertThat(input).as("bundled schema %s", SCHEMA_RESOURCE).isNotNull();
            JsonNode schema = objectMapper.readTree(input);
            for (JsonNode branch : schema.path("allOf")) {
                JsonNode data = branch.path("properties").path("data");
                if (data.isObject()) {
                    assertThat(data.path("properties").isObject())
                            .as("omsstats data declares its properties")
                            .isTrue();
                    return data;
                }
            }
            throw new AssertionError("omsstats schema declares no data properties");
        } catch (java.io.IOException unreadable) {
            throw new AssertionError("Unable to read " + SCHEMA_RESOURCE, unreadable);
        }
    }
}
