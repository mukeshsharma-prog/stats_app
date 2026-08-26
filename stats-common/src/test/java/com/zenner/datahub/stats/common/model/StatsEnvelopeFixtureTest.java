package com.zenner.datahub.stats.common.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StatsEnvelopeFixtureTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @ParameterizedTest(name = "{0}")
    @MethodSource("validRecords")
    void roundTripsEveryExpandedValidRecord(String description, JsonNode record) throws Exception {
        StatsEnvelope envelope = OBJECT_MAPPER.treeToValue(record, StatsEnvelope.class);

        // Reparse the serialized bytes before comparing trees so Jackson's
        // internal IntNode/LongNode choice cannot turn equal JSON numbers into a
        // false negative.
        JsonNode roundTrip = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsBytes(envelope));

        assertThat(roundTrip).isEqualTo(record);
    }

    @Test
    void expandsTheValidDirectoryToTwentySevenRecords() {
        assertThat(validRecords()).hasSize(27);
    }

    static List<Arguments> validRecords() {
        Path directory = resourceDirectory("valid");
        try (Stream<Path> paths = Files.list(directory)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .flatMap(path -> records(path).stream())
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Mirrors docs/schemas/validate-fixtures.py records() for valid fixtures. */
    private static List<Arguments> records(Path path) {
        try {
            JsonNode document = OBJECT_MAPPER.readTree(path.toFile());
            String filename = path.getFileName().toString();

            if (filename.equals("approved-types.json")) {
                List<Arguments> records = new ArrayList<>();
                document.properties().forEach(entry -> records.add(
                        Arguments.of(filename + "#" + entry.getKey(), entry.getValue())));
                return records;
            }

            if (filename.contains("tenant-isolation-pair")) {
                return List.of(
                        Arguments.of(filename + "#record_a", document.path("record_a")),
                        Arguments.of(filename + "#record_b", document.path("record_b")));
            }

            ObjectNode record = ((ObjectNode) document).deepCopy();
            List<String> metadataFields = new ArrayList<>();
            record.fieldNames().forEachRemaining(field -> {
                if (field.startsWith("$")) {
                    metadataFields.add(field);
                }
            });
            record.remove(metadataFields);
            return List.of(Arguments.of(filename, record));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Path resourceDirectory(String name) {
        try {
            return Path.of(Objects.requireNonNull(
                    StatsEnvelopeFixtureTest.class.getClassLoader().getResource(name),
                    "Missing fixture directory: " + name).toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Invalid fixture directory URI", exception);
        }
    }
}
