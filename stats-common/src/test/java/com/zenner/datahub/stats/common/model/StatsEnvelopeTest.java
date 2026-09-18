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
    void clsboxMustBeTwelveHexadecimalCharacters() throws Exception {
        StatsEnvelope valid = OBJECT_MAPPER.treeToValue(
                readFixture("valid/lorastats-valid.json"), StatsEnvelope.class);
        StatsEnvelope invalid = new StatsEnvelope(
                valid.uid(),
                valid.tenant(),
                valid.maintenant(),
                "not-a-clsbox",
                valid.type(),
                valid.version(),
                valid.ts(),
                valid.data(),
                valid.meta(),
                valid.status());

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Set<ConstraintViolation<StatsEnvelope>> violations = validator.validate(invalid);

            assertThat(violations)
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("clsbox");
        }
    }

    @Test
    void preservesTheWholeProductionTimestampBlockThroughTheTypedView() throws Exception {
        // ADR-004 requires publication to be byte-identical to what was received, and
        // ADR-003's per-partition duplicate suppression depends on that. A typed view
        // that silently dropped ts.datastore, ts.converter or ts.expiresOn would break
        // it without failing anything. Production sends all three on every record.
        JsonNode fixture = readFixture("valid/omsstats-ts-converter-present.json");
        ObjectNode record = ((ObjectNode) fixture).deepCopy();
        record.remove("$comment");

        StatsEnvelope envelope = OBJECT_MAPPER.treeToValue(record, StatsEnvelope.class);
        JsonNode roundTrip = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsBytes(envelope));

        assertThat(roundTrip).isEqualTo(record);
        assertThat(envelope.ts().converter()).isNotNull();
        assertThat(envelope.ts().datastore()).isNotNull();
        assertThat(envelope.ts().expiresOn().asText()).isEqualTo("2026-08-27T12:25:37.071Z");
        assertThat(envelope.version()).isEqualTo(1);
        assertThat(envelope.status()).containsExactly("conversion ok");
    }

    @Test
    void omitsAbsentOptionalMembersRatherThanSerialisingThemAsNull() throws Exception {
        // The valid fixture set predates version/status/datastore/converter. Without
        // JsonInclude.NON_NULL every one of those records would round-trip with four
        // extra null members and stop matching what was received.
        StatsEnvelope envelope = OBJECT_MAPPER.treeToValue(
                readFixture("valid/lorastats-valid.json"), StatsEnvelope.class);

        JsonNode roundTrip = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsBytes(envelope));

        assertThat(roundTrip.has("version")).isFalse();
        assertThat(roundTrip.has("status")).isFalse();
        assertThat(roundTrip.at("/ts").has("datastore")).isFalse();
        assertThat(roundTrip.at("/ts").has("converter")).isFalse();
    }

    private static JsonNode readFixture(String name) throws IOException {
        try (InputStream input = StatsEnvelopeTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(input).as("fixture %s", name).isNotNull();
            return OBJECT_MAPPER.readTree(input);
        }
    }
}
