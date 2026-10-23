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
 * Keeps the writer's field mapping in step with the normative schema.
 *
 * <p>{@code MAPPED_DATA_FIELDS} decides what goes into a typed column and what is
 * preserved in {@code data_extra}. If the schema gains a member and this set
 * does not, that member silently starts landing in {@code data_extra} instead of its
 * column — valid JSON, no error, and invisible until someone queries for a column
 * that is always null. Deriving the expectation from
 * {@code docs/schemas/lorastats-v1.schema.json} — the same file the Python CI gate
 * and the Java validator use — is what makes that a build failure instead.</p>
 */
class LorastatsColumnMappingTest {

    private static final String SCHEMA_RESOURCE = "schemas/lorastats-v1.schema.json";

    @Test
    void mapsExactlyTheDataMembersTheSchemaNames() {
        assertThat(LorastatsBatchWriter.MAPPED_DATA_FIELDS)
                .containsExactlyInAnyOrderElementsOf(schemaDataFields());
    }

    @Test
    void theInsertStatementNamesEveryMappedField() {
        String insertSql =
                (String) ReflectionTestUtils.getField(LorastatsBatchWriter.class, "INSERT_SQL");

        assertThat(insertSql).isNotNull();
        for (String field : LorastatsBatchWriter.MAPPED_DATA_FIELDS) {
            assertThat(insertSql).as("column for data.%s", field).contains(field);
        }
    }

    /**
     * The envelope members that are preserved but never read must reach the database
     * too. They are not in {@code data}, so the mapping test above cannot see them,
     * and every one of them is optional — which is exactly why dropping them stayed
     * invisible.
     */
    @Test
    void theInsertStatementCarriesTheAdr003EnvelopeColumns() {
        String insertSql =
                (String) ReflectionTestUtils.getField(LorastatsBatchWriter.class, "INSERT_SQL");

        assertThat(insertSql)
                .contains("record_version")
                .contains("schema_version")
                .contains("passthrough")
                .contains("data_extra")
                .contains("ON CONFLICT (ts_device, uid) DO NOTHING");
    }

    /**
     * The union of the three dialect branches, folded back to canonical (bare) spelling.
     *
     * <p>The schema's {@code data} is a {@code oneOf} of three branches
     * rather than one property map, and the union is the right question to ask of it: a
     * member that has a typed column must be named by whichever branch can carry it, and
     * the Writer normalises {@code lora_}-prefixed names to bare ones before the INSERT,
     * so {@code lora_rx_nr_pkts} and {@code rx_nr_pkts} are one column and not two.
     * Reading a single branch would silently under-count and turn this guard into a test
     * of dialect A alone.</p>
     */
    private static Set<String> schemaDataFields() {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream input =
                LorastatsColumnMappingTest.class
                        .getClassLoader()
                        .getResourceAsStream(SCHEMA_RESOURCE)) {
            assertThat(input).as("bundled schema %s", SCHEMA_RESOURCE).isNotNull();
            JsonNode schema = objectMapper.readTree(input);

            JsonNode dialects = null;
            for (JsonNode branch : schema.path("allOf")) {
                JsonNode candidate = branch.path("properties").path("data").path("oneOf");
                if (candidate.isArray()) {
                    dialects = candidate;
                }
            }
            assertThat(dialects)
                    .as("data.oneOf — the closed three-dialect enumeration")
                    .isNotNull();
            assertThat(dialects)
                    .as("the enumeration is CLOSED AT THREE; a fourth branch is a decision")
                    .hasSize(3);

            List<String> names = new ArrayList<>();
            for (JsonNode reference : dialects) {
                String pointer = reference.path("$ref").asText().replace("#/", "/");
                JsonNode properties = schema.at(pointer).path("properties");
                assertThat(properties.isObject())
                        .as("dialect branch %s declares its properties", pointer)
                        .isTrue();
                properties
                        .fieldNames()
                        .forEachRemaining(
                                name ->
                                        names.add(
                                                name.startsWith("lora_")
                                                        ? name.substring("lora_".length())
                                                        : name));
            }
            return Set.copyOf(names);
        } catch (Exception unreadable) {
            throw new AssertionError("Unable to read " + SCHEMA_RESOURCE, unreadable);
        }
    }
}
