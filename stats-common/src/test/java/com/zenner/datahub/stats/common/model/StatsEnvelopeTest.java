package com.zenner.datahub.stats.common.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StatsEnvelopeTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void acceptsUnknownEnvelopeFieldsEvenWithStrictGlobalJacksonConfiguration() throws Exception {
        ObjectNode fixture = (ObjectNode) readFixture("valid/lorastats-valid.json");
        fixture.put("future_envelope_extension", "accepted");

        StatsEnvelope envelope = OBJECT_MAPPER.treeToValue(fixture, StatsEnvelope.class);

        assertThat(envelope.uid()).isEqualTo(fixture.path("uid").asText());
        assertThat(envelope.type()).isEqualTo(StatType.LORASTATS);
    }

    @Test
    void preservesUnknownDataFields() throws Exception {
        ObjectNode fixture = (ObjectNode) readFixture("valid/lorastats-valid.json");
        ((ObjectNode) fixture.path("data")).put("future_counter", 17);

        StatsEnvelope envelope = OBJECT_MAPPER.treeToValue(fixture, StatsEnvelope.class);
        JsonNode roundTrip = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsBytes(envelope));

        assertThat(roundTrip).isEqualTo(fixture);
        assertThat(roundTrip.at("/data/future_counter").asInt()).isEqualTo(17);
    }

    @Test
    void gatewayMustBeTwelveHexadecimalCharacters() throws Exception {
        StatsEnvelope valid = OBJECT_MAPPER.treeToValue(
                readFixture("valid/lorastats-valid.json"), StatsEnvelope.class);
        StatsEnvelope invalid = new StatsEnvelope(
                valid.uid(),
                valid.tenant(),
                valid.maintenant(),
                "not-a-gateway",
                valid.type(),
                valid.ts(),
                valid.data());

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Set<ConstraintViolation<StatsEnvelope>> violations = validator.validate(invalid);

            assertThat(violations)
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("gateway");
        }
    }

    private static JsonNode readFixture(String name) throws IOException {
        try (InputStream input = StatsEnvelopeTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(input).as("fixture %s", name).isNotNull();
            return OBJECT_MAPPER.readTree(input);
        }
    }
}
