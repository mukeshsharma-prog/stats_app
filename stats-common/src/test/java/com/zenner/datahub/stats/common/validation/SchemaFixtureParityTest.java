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
    void expandsToTheSameNinetyOutcomesAsThePythonOracle() {
        assertThat(fixtureCases()).hasSize(90);
        assertThat(fixtureCases())
                .extracting(arguments -> (String) arguments.get()[0])
                .noneMatch(description -> description.contains("malformed/"));
    }

    /**
     * Mirrors the directory expectations and record expansion of
     * docs/schemas/validate-fixtures.py exactly: valid/ and edge/ must validate,
     * invalid/ must fail, malformed/ is not schema-testable.
     *
     * <p>The Python gate additionally asserts the twelve {@code $expect} markers —
     * {@code "input-reject"} on the five clock-skew fixtures (ADR-003 D9),
     * {@code "writer-dlq"} on the unknown fourth dialect (DL-105 rule 4),
     * {@code "writer-range-reject"} on the dialect-B counter that overflows a
     * {@code BIGINT} (DL-105 rule 3), and {@code "writer-skip"} on the five late-arrival
     * fixtures (D5b → DL-112, T-026), and {@code "writer-dlq"} again on the ltestats
     * record that is half a measurement (DL-146) — none of which are schema cases. So it
     * reports 103 assertions where this test expands to 90 outcomes. That gap is
     * deliberate — do not "fix" either number to match the other.</p>
     *
     * <p>The marker count stayed at seven on 8 Sep 2026 while its composition changed
     * completely: the two lorastats dialect fixtures lost their {@code writer-dlq}
     * markers when T-063 made them valid, and two new fixtures took their place at the
     * two boundaries the widened schema created — the dialect nobody has explained, and
     * the value the schema admits and the column cannot hold.</p>
     *
     * <p>It moved to twelve on 10 Sep 2026, and the five that joined were already in the
     * set: the late-arrival fixtures had <strong>no</strong> marker, so they silently
     * asserted "the schema accepts this" and said nothing at all about a decided,
     * signed, deliberate data-loss path. That is the identical gap the clock-skew
     * fixtures had before {@code "input-reject"} existed — an undecided question with
     * "accept" quietly baked into a green assertion.</p>
     *
     * <p>84 → 90 on 18 Sep 2026 (DL-146): six fixtures re-derived from live data — the
     * two ltestats shapes the old schema dead-lettered, the mixed shape the new one must
     * still dead-letter, a lorastats dialect-B record with an integer extra member,
     * Heimdal's {@code meta}/{@code ts.deviceOrig}, and {@code expiresOn} as Extended
     * JSON. Thirteen markers now.</p>
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
