package com.zenner.datahub.stats.common.validation;

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

class SchemaFixtureParityTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final StatsSchemaValidator VALIDATOR = new StatsSchemaValidator();

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureCases")
    void matchesThePythonDraft202012Oracle(
            String description,
            boolean expectedValid,
            JsonNode record) {
        ValidationResult result = VALIDATOR.validate(record);

        assertThat(result.valid())
                .as("%s should be %s", description, expectedValid ? "valid" : "invalid")
                .isEqualTo(expectedValid);
        assertThat(result.schemaVersion()).isEqualTo("v1");
        if (expectedValid) {
            assertThat(result.errors()).isEmpty();
        } else {
            assertThat(result.errors())
                    .as("every invalid schema-testable record has field-level errors")
                    .isNotEmpty();
        }
    }

    @Test
    void expandsToTheSameEightyOutcomesAsThePythonOracle() {
        assertThat(fixtureCases()).hasSize(80);
        assertThat(fixtureCases())
                .extracting(arguments -> (String) arguments.get()[0])
                .noneMatch(description -> description.contains("malformed/"));
    }

    /**
     * Mirrors the directory expectations and record expansion of
     * docs/schemas/validate-fixtures.py exactly: valid/ and edge/ must validate,
     * invalid/ must fail, malformed/ is not schema-testable.
     *
     * <p>The Python gate additionally asserts the seven {@code $expect} markers —
     * {@code "input-reject"} on the five clock-skew fixtures (ADR-003 D9), and
     * {@code "writer-dlq"} on the two lorastats dialect fixtures (ADR-007 §4) — none
     * of which are schema cases. So it reports 87 assertions where this test expands
     * to 80 outcomes. That gap is deliberate — do not "fix" either number to match
     * the other.</p>
     */
    static List<Arguments> fixtureCases() {
        List<Path> files = new ArrayList<>();
        for (String directory : List.of("valid", "edge", "invalid")) {
            try (Stream<Path> paths = Files.list(resourceDirectory(directory))) {
                paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .forEach(files::add);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        return files.stream()
                .sorted(Comparator.comparing(path -> relativeDescription(path, "")))
                .flatMap(path -> records(path).stream())
                .toList();
    }

    private static List<Arguments> records(Path path) {
        try {
            JsonNode document = OBJECT_MAPPER.readTree(path.toFile());
            String filename = path.getFileName().toString();
            String directory = path.getParent().getFileName().toString();
            boolean expectedValid = (directory.equals("valid") || directory.equals("edge"))
                    && !filename.equals("validation-failures.json");

            if (filename.equals("approved-types.json") || filename.equals("validation-failures.json")) {
                List<Arguments> records = new ArrayList<>();
                document.properties().forEach(entry -> records.add(Arguments.of(
                        relativeDescription(path, entry.getKey()),
                        expectedValid,
                        entry.getValue())));
                return records;
            }

            if (filename.contains("tenant-isolation-pair")) {
                return List.of(
                        Arguments.of(
                                relativeDescription(path, "record_a"),
                                expectedValid,
                                document.path("record_a")),
                        Arguments.of(
                                relativeDescription(path, "record_b"),
                                expectedValid,
                                document.path("record_b")));
            }

            ObjectNode record = ((ObjectNode) document).deepCopy();
            List<String> metadataFields = new ArrayList<>();
            record.fieldNames().forEachRemaining(field -> {
                if (field.startsWith("$")) {
                    metadataFields.add(field);
                }
            });
            record.remove(metadataFields);
            return List.of(Arguments.of(relativeDescription(path, ""), expectedValid, record));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String relativeDescription(Path path, String label) {
        String base = path.getParent().getFileName() + "/" + path.getFileName();
        return label.isEmpty() ? base : base + "#" + label;
    }

    private static Path resourceDirectory(String name) {
        try {
            return Path.of(Objects.requireNonNull(
                    SchemaFixtureParityTest.class.getClassLoader().getResource(name),
                    "Missing fixture directory: " + name).toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Invalid fixture directory URI", exception);
        }
    }
}
