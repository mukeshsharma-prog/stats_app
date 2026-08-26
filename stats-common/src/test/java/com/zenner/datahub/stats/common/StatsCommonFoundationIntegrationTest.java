package com.zenner.datahub.stats.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.error.ErrorCode;
import com.zenner.datahub.stats.common.error.ProblemDetails;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.common.model.StatsEnvelope;
import com.zenner.datahub.stats.common.validation.StatsSchemaValidator;
import com.zenner.datahub.stats.common.validation.ValidationResult;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Verifies the intended seams between the four stats-common tasks.
 *
 * <p>The future Input module must validate and publish the original JSON rather
 * than reserializing {@link StatsEnvelope}. The typed envelope is only the safe
 * view used to choose topology names and read common fields.</p>
 */
class StatsCommonFoundationIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final StatsSchemaValidator validator = new StatsSchemaValidator();

    @Test
    void validRecordConnectsSchemaEnvelopeAndTopologyWithoutMutatingTheRawRecord()
            throws Exception {
        ObjectNode rawRecord = (ObjectNode) readFixture("valid/ltestats-valid.json");
        rawRecord.put("futureEnvelopeMember", "kept-on-the-raw-publication-path");
        ((ObjectNode) rawRecord.path("data")).put("futureDataMember", 42);
        JsonNode originalRecord = rawRecord.deepCopy();

        ValidationResult validation = validator.validate(rawRecord); // T-004
        StatsEnvelope envelope = OBJECT_MAPPER.treeToValue(rawRecord, StatsEnvelope.class); // T-001

        assertThat(validation.valid()).isTrue();
        assertThat(validation.schemaVersion()).isEqualTo("v1");
        assertThat(envelope.type()).isEqualTo(StatType.LTESTATS);
        assertThat(envelope.ts().device().getEpochSecond()).isEqualTo(1_787_054_100L);
        assertThat(envelope.data().path("futureDataMember").asInt()).isEqualTo(42);

        assertThat(AmqpTopology.routingKey(envelope.type())).isEqualTo("ltestats"); // T-002
        assertThat(AmqpTopology.mainQueue(envelope.type()))
                .isEqualTo("app_statsstore.ltestats");
        assertThat(AmqpTopology.retryQueue(envelope.type(), 3))
                .isEqualTo("app_statsstore.ltestats.retry.3");
        assertThat(AmqpTopology.deadLetterQueue(envelope.type()))
                .isEqualTo("app_statsstore.ltestats.dlq");

        // Validation and typed inspection must not alter the JsonNode that the
        // future Input publisher retains for byte-identical publication (D6).
        assertThat(rawRecord).isEqualTo(originalRecord);
    }

    @Test
    void invalidRecordConnectsSchemaErrorsToProblemDetailsWithoutLeakingPayloadValues()
            throws Exception {
        JsonNode rawRecord = readFixture("invalid/ltestats-wrong-type.json");
        String rejectedPlmn = rawRecord.at("/data/plmn").asText();
        String imsi = rawRecord.at("/data/imsi").asText();

        ValidationResult validation = validator.validate(rawRecord); // T-004
        ProblemDetails problem = ProblemDetails.validationFailure( // T-003
                ErrorCode.INVALID_PARAMETER,
                StatType.fromWireName(rawRecord.path("type").asText()), // T-001
                validation.errors(),
                "/stats",
                "trace-foundation-test");
        String responseJson = OBJECT_MAPPER.writeValueAsString(problem);

        assertThat(validation.valid()).isFalse();
        assertThat(problem.status()).isEqualTo(400);
        assertThat(problem.errors()).isEqualTo(validation.errors());
        assertThat(responseJson).contains("/data/plmn", "invalid_type");
        assertThat(responseJson).doesNotContain(rejectedPlmn, imsi);
    }

    private static JsonNode readFixture(String name) throws IOException {
        try (InputStream input = StatsCommonFoundationIntegrationTest.class
                .getClassLoader()
                .getResourceAsStream(name)) {
            assertThat(input).as("fixture %s", name).isNotNull();
            return OBJECT_MAPPER.readTree(input);
        }
    }
}
