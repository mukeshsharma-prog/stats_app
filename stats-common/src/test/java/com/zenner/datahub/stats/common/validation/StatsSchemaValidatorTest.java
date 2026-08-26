package com.zenner.datahub.stats.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.path.PathType;
import com.networknt.schema.regex.JoniRegularExpressionFactory;
import com.zenner.datahub.stats.common.error.FieldError;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatsSchemaValidatorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final StatsSchemaValidator validator = new StatsSchemaValidator();

    @Test
    void acceptsAValidRecordAndReportsTheSelectedSchemaVersion() throws IOException {
        ValidationResult result = validator.validate(readFixture("valid/ltestats-valid.json"));

        assertThat(result.valid()).isTrue();
        assertThat(result.schemaVersion()).isEqualTo("v1");
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void rejectsAnUnknownTypeBeforeUsingItsLiteralAndDoesNotEchoIt() throws Exception {
        String attackerControlledType = "../../tenant-beta/app_statsstore/SELECT/Exception";
        JsonNode record = OBJECT_MAPPER.readTree("""
                {
                  "type": "../../tenant-beta/app_statsstore/SELECT/Exception"
                }
                """);

        ValidationResult result = validator.validate(record);
        String json = OBJECT_MAPPER.writeValueAsString(result);

        assertThat(result.valid()).isFalse();
        assertThat(result.schemaVersion()).isEqualTo("v1");
        assertThat(result.errors()).containsExactly(
                FieldError.of(
                        "/type",
                        "unknown_stat_type",
                        FieldError.SafeMessage.UNSUPPORTED_STAT_TYPE));
        assertThat(json).doesNotContain(attackerControlledType);
        assertThat(json).doesNotContain("tenant-beta", "app_statsstore", "SELECT", "Exception");
    }

    @Test
    void distinguishesMissingAndWronglyTypedTypeBeforeSchemaSelection() throws Exception {
        ValidationResult missing = validator.validate(OBJECT_MAPPER.readTree("{}"));
        ValidationResult wrongType = validator.validate(OBJECT_MAPPER.readTree("{\"type\": 7}"));
        ValidationResult wrongRoot = validator.validate(OBJECT_MAPPER.readTree("[]"));

        assertThat(missing.errors()).extracting(FieldError::field, FieldError::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("/type", "missing"));
        assertThat(wrongType.errors()).extracting(FieldError::field, FieldError::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("/type", "invalid_type"));
        assertThat(wrongRoot.errors()).extracting(FieldError::field, FieldError::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("/", "invalid_type"));
        assertThat(List.of(missing, wrongType, wrongRoot))
                .allSatisfy(result -> assertThat(result.schemaVersion()).isEqualTo("v1"));
    }

    @Test
    void mapsRequiredTypeAndParameterFailuresToPointersAndStaticMessages() throws IOException {
        ValidationResult missing = validator.validate(readFixture("invalid/ltestats-missing-required.json"));
        ValidationResult wrongType = validator.validate(readFixture("invalid/ltestats-wrong-type.json"));
        ValidationResult badPattern = validator.validate(readFixture("invalid/envelope-gateway-not-hex.json"));
        ValidationResult outOfRange = validator.validate(readFixture("invalid/envelope-ts-in-milliseconds.json"));
        ValidationResult forbiddenConverter = validator.validate(readFixture("invalid/envelope-oms-ts-converter.json"));

        assertThat(missing.errors()).contains(FieldError.of(
                "/data/rsrp", "missing", FieldError.SafeMessage.MANDATORY_FIELD_ABSENT));
        assertThat(wrongType.errors()).contains(FieldError.of(
                "/data/plmn", "invalid_type", FieldError.SafeMessage.INVALID_FIELD_TYPE));
        assertThat(badPattern.errors()).contains(FieldError.of(
                "/gateway", "invalid_parameter", FieldError.SafeMessage.INVALID_PARAMETER));
        assertThat(outOfRange.errors())
                .extracting(FieldError::field, FieldError::code)
                .contains(org.assertj.core.groups.Tuple.tuple("/ts/device", "invalid_parameter"));
        assertThat(forbiddenConverter.errors()).contains(FieldError.of(
                "/ts", "invalid_parameter", FieldError.SafeMessage.INVALID_PARAMETER));
    }

    @Test
    void discardsLibraryMessagesAndRejectedRuntimeValues() throws Exception {
        JsonNode fixture = readFixture("invalid/ltestats-wrong-type.json");
        String offendingValue = fixture.at("/data/plmn").asText();
        String imsi = fixture.at("/data/imsi").asText();

        ValidationResult result = validator.validate(fixture);
        String json = OBJECT_MAPPER.writeValueAsString(result);

        assertThat(json).contains("/data/plmn", "invalid_type", "Field has an invalid type.");
        assertThat(json).doesNotContain(offendingValue);
        assertThat(json).doesNotContain(imsi);
        assertThat(json).doesNotContain("must be integer", "Exception", "SELECT", "app_statsstore", "tenant-beta");
    }

    @Test
    void registryIsOfflineUsesJsonPointersAndEcma262RegexWithoutFormatAssertions() {
        assertThat(validator.schemaRegistry().getSchemaLoader().getResourceLoaders())
                .hasSize(1)
                .noneMatch(loader -> loader.getClass().getSimpleName().equals("IriResourceLoader"));
        assertThat(validator.schemaRegistry().getSchemaRegistryConfig().getPathType())
                .isEqualTo(PathType.JSON_POINTER);
        assertThat(validator.schemaRegistry().getSchemaRegistryConfig().getRegularExpressionFactory())
                .isSameAs(JoniRegularExpressionFactory.getInstance());
        assertThat(validator.schemaRegistry().getSchemaRegistryConfig().getFormatAssertionsEnabled())
                .isFalse();
    }

    private static JsonNode readFixture(String name) throws IOException {
        try (InputStream input = StatsSchemaValidatorTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(input).as("fixture %s", name).isNotNull();
            return OBJECT_MAPPER.readTree(input);
        }
    }
}
