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

    /**
     * Four fixtures joined valid/ with T-063 and T-064 on 8 Sep 2026: the two production
     * dialect records that moved out of invalid/ when DL-105 was built, the BIGINT
     * overflow record the Writer refuses after the schema admits it, and the non-OK
     * upstream status of DL-108.
     *
     * <p>Five more on 18 Sep 2026 with DL-146, all shaped from live data: the two
     * ltestats records the old schema dead-lettered (2G without rsrp/rsrq, and
     * etxt-only), a dialect-B lorastats record with an integer extra member, Heimdal's
     * {@code meta} / {@code ts.deviceOrig}, and {@code expiresOn} as Extended JSON. The
     * last two are the ones this round-trip test exists for: before DL-146 the typed
     * view dropped {@code meta} and {@code deviceOrig} silently and threw on the
     * object-form {@code expiresOn}.</p>
     */
    @Test
    void expandsTheValidDirectoryToFortyFiveRecords() {
        assertThat(validRecords()).hasSize(45);
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
