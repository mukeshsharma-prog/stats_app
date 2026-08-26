package com.zenner.datahub.stats.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.common.error.FieldError.SafeMessage;
import com.zenner.datahub.stats.common.model.StatType;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProblemDetailsTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void validationFactoryUsesCatalogueMetadataAndSerializesTheContractShape() {
        ProblemDetails problem = ProblemDetails.validationFailure(
                ErrorCode.INVALID_PARAMETER,
                StatType.LTESTATS,
                List.of(FieldError.of(
                        "/data/plmn", "invalid_type", SafeMessage.INVALID_FIELD_TYPE)),
                "/stats",
                "trace-123");

        JsonNode json = OBJECT_MAPPER.valueToTree(problem);

        assertThat(problem.type()).isEqualTo(ErrorCode.INVALID_PARAMETER.typeUri());
        assertThat(problem.title()).isEqualTo("Invalid request parameter");
        assertThat(problem.status()).isEqualTo(400);
        assertThat(problem.detail()).isEqualTo("1 field failed validation.");
        assertThat(json.path("instance").asText()).isEqualTo("/stats");
        assertThat(json.path("statType").asText()).isEqualTo("ltestats");
        assertThat(json.path("traceId").asText()).isEqualTo("trace-123");
        assertThat(json.path("errors")).hasSize(1);
    }

    @Test
    void omitsEveryAbsentOptionalPropertyAndExposesTheProblemContentType() {
        ProblemDetails problem = ProblemDetails.validationFailure(
                ErrorCode.UNKNOWN_STAT_TYPE,
                List.of(FieldError.of("/type", "unknown_stat_type", SafeMessage.UNSUPPORTED_STAT_TYPE)),
                null,
                null);

        JsonNode json = OBJECT_MAPPER.valueToTree(problem);

        assertThat(ProblemDetails.CONTENT_TYPE).isEqualTo("application/problem+json");
        assertThat(json.has("instance")).isFalse();
        assertThat(json.has("statType")).isFalse();
        assertThat(json.has("traceId")).isFalse();
    }

    @Test
    void copiesTheErrorListDefensively() {
        List<FieldError> mutableErrors = new ArrayList<>();
        mutableErrors.add(FieldError.of("/tenant", "missing", SafeMessage.MANDATORY_FIELD_ABSENT));

        ProblemDetails problem = ProblemDetails.validationFailure(
                ErrorCode.INVALID_PARAMETER, mutableErrors, "/stats", "trace-123");
        mutableErrors.clear();

        assertThat(problem.errors()).hasSize(1);
        assertThatThrownBy(() -> problem.errors().add(FieldError.of("/gateway", "missing")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidProblemStatusAndInvalidValidationFactoryUse() {
        assertThatThrownBy(() -> new ProblemDetails(
                        URI.create("https://stats.example/errors/not_an_error"),
                        "Not an error",
                        200,
                        null,
                        null,
                        null,
                        null,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("status must be between 400 and 599");

        assertThatThrownBy(() -> ProblemDetails.validationFailure(
                        ErrorCode.SERVICE_UNAVAILABLE,
                        List.of(FieldError.of("/type", "invalid_parameter")),
                        "/stats",
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("validation failures must use an HTTP 400 error code");

        assertThatThrownBy(() -> ProblemDetails.validationFailure(
                        ErrorCode.INVALID_PARAMETER, List.of(), "/stats", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("validation failures require at least one field error");
    }

    @Test
    void validationProblemDoesNotLeakSensitiveOrInternalValues() throws IOException {
        JsonNode invalidRecord = readFixture("invalid/ltestats-wrong-type.json");
        String imsiValue = invalidRecord.at("/data/imsi").asText();

        ProblemDetails problem = ProblemDetails.validationFailure(
                ErrorCode.INVALID_PARAMETER,
                StatType.LTESTATS,
                List.of(FieldError.of(
                        "/data/plmn", "invalid_type", SafeMessage.INVALID_FIELD_TYPE)),
                "/stats",
                "trace-123");
        String json = OBJECT_MAPPER.writeValueAsString(problem);

        List<LeakProbe> denylist = List.of(
                // Prevents a device-identifier/IMSI leak from the rejected payload.
                new LeakProbe(imsiValue, "device identifier"),
                // Prevents an internal RabbitMQ queue-name leak.
                new LeakProbe("app_statsstore", "internal queue name"),
                // Prevents a database query or SQL-fragment leak.
                new LeakProbe("SELECT", "SQL text"),
                // Prevents an exception or stack-trace leak.
                new LeakProbe("Exception", "exception text"),
                // Prevents disclosure of another tenant's identity or data existence.
                new LeakProbe("tenant-beta", "cross-tenant information"));

        for (LeakProbe denied : denylist) {
            assertThat(json)
                    .as("prevents leaking %s", denied.leakClass())
                    .doesNotContain(denied.value());
        }

        assertThat(json).contains("invalid_type", "/data/plmn", "Field has an invalid type.");
    }

    private static JsonNode readFixture(String name) throws IOException {
        try (InputStream input = ProblemDetailsTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(input).as("fixture %s", name).isNotNull();
            return OBJECT_MAPPER.readTree(input);
        }
    }

    private record LeakProbe(String value, String leakClass) {}
}
