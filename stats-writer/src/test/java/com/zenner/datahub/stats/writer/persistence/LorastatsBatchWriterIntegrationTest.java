package com.zenner.datahub.stats.writer.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.common.model.StatsEnvelope;
import com.zenner.datahub.stats.common.model.Timestamps;
import com.zenner.datahub.stats.testsupport.PostgresSupport;
import com.zenner.datahub.stats.writer.StatsWriterApplication;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real PostgreSQL proof of T-021 per-record precision and T-022 idempotency. */
@SpringBootTest(
        classes = StatsWriterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "stats.writer.stat-type=netstats",
            "stats.retention.schedule=-",
            // These tests create and drop their own partitions on fixed far-future
            // days. The startup pre-creation is exercised by
            // PartitionBootstrapIntegrationTest, which leaves it on.
            "stats.retention.pre-create-on-startup=false"
        })
@Import(PostgresSupport.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LorastatsBatchWriterIntegrationTest {

    private static final String TEST_PARTITION = "lorastats_t021_test_day";
    private static final Instant DEVICE_TIME = Instant.parse("2097-07-12T12:00:00Z");

    @Autowired
    private LorastatsBatchWriter writer;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    void createPartition() {
        jdbc.execute(
                """
                CREATE TABLE %s PARTITION OF lorastats
                FOR VALUES FROM ('2097-07-12 00:00:00+00') TO ('2097-07-13 00:00:00+00')
                """.formatted(TEST_PARTITION));
    }

    @AfterAll
    void dropPartition() {
        jdbc.execute("DROP TABLE IF EXISTS " + TEST_PARTITION);
    }

    @Test
    void attributesOneBadRecordWithinOneHundredAndCommitsTheOtherNinetyNine() {
        List<StatsEnvelope> records = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            ObjectNode data = completeData(index);
            if (index == 37) {
                data.remove("tx_success");
            }
            records.add(envelope("batch-" + index, "tenant-a", data));
        }

        List<RecordWriteResult> results = writer.write(records);

        assertThat(results).hasSize(100);
        assertThat(results)
                .filteredOn(result -> result.status() == RecordWriteStatus.FAILED)
                .singleElement()
                .satisfies(
                        result -> {
                            assertThat(result.index()).isEqualTo(37);
                            assertThat(result.failureOptional().orElseThrow())
                                    .hasMessageContaining("data.tx_success");
                        });
        assertThat(results)
                .filteredOn(result -> result.status() == RecordWriteStatus.INSERTED)
                .hasSize(99);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM lorastats WHERE uid LIKE 'batch-%'",
                                Integer.class))
                .isEqualTo(99);
    }

    @Test
    void suppressesAndMetersThePartitionKeyDuplicateEvenAcrossTenants() {
        StatsEnvelope original = envelope("duplicate-key", "tenant-original", completeData(1));
        StatsEnvelope collision = envelope("duplicate-key", "tenant-other", completeData(2));
        double before = duplicateCount();

        assertThat(writer.write(List.of(original)))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);
        assertThat(writer.write(List.of(collision)))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.DUPLICATE_SUPPRESSED);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM lorastats WHERE ts_device = ? AND uid = ?",
                                Integer.class,
                                Timestamp.from(DEVICE_TIME),
                                "duplicate-key"))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT tenant FROM lorastats WHERE ts_device = ? AND uid = ?",
                                String.class,
                                Timestamp.from(DEVICE_TIME),
                                "duplicate-key"))
                .isEqualTo("tenant-original");
        assertThat(duplicateCount()).isEqualTo(before + 1.0d);
    }

    @Test
    void mapsEveryV1PayloadColumn() {
        StatsEnvelope record = envelope("all-columns", "tenant-a", completeData(10));

        assertThat(writer.write(List.of(record)))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);

        Map<String, Object> stored =
                jdbc.queryForMap(
                        """
                        SELECT schema_version, rx_nr_pkts, rx_short_pkt, fsk_received,
                               rx_type_arol, timespan
                        FROM lorastats WHERE ts_device = ? AND uid = ?
                        """,
                        Timestamp.from(DEVICE_TIME),
                        "all-columns");

        assertThat(((Number) stored.get("schema_version")).intValue()).isEqualTo(1);
        assertThat(stored)
                .containsEntry("rx_nr_pkts", 11L)
                .containsEntry("rx_short_pkt", 14L)
                .containsEntry("fsk_received", 25L)
                .containsEntry("rx_type_arol", 26L)
                .containsEntry("timespan", 27L);
    }

    /**
     * T-058. ADR-004 and ADR-007 §2: the envelope members production sends on every
     * record are preserved and never read. Dropping them at the INSERT would make the
     * round trip lossy while every schema test still passed, because none of these
     * members is required.
     */
    @Test
    void preservesRecordVersionAndTheFivePassthroughEnvelopeMembers() {
        Instant datastore = DEVICE_TIME.plusSeconds(1);
        Instant converter = DEVICE_TIME.plusSeconds(2);
        StatsEnvelope record =
                new StatsEnvelope(
                        "passthrough-proof",
                        "tenant-a",
                        "management-parent",
                        "A1B2C3D4E5F6",
                        StatType.LORASTATS,
                        7,
                        new Timestamps(
                                DEVICE_TIME,
                                DEVICE_TIME,
                                datastore,
                                converter,
                                "2097-07-13T12:28:59.074Z"),
                        completeData(0),
                        List.of("conversion ok"));

        assertThat(writer.write(List.of(record)))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);

        Map<String, Object> stored =
                jdbc.queryForMap(
                        "SELECT record_version, passthrough FROM lorastats "
                                + "WHERE ts_device = ? AND uid = ?",
                        Timestamp.from(DEVICE_TIME),
                        "passthrough-proof");

        assertThat(((Number) stored.get("record_version")).intValue()).isEqualTo(7);

        JsonNode passthrough = readJson(stored.get("passthrough"));
        // Epoch SECONDS, as the wire sends them -- storing millis or an ISO string
        // here would silently change what a round trip returns.
        assertThat(passthrough.path("ts").path("datastore").asLong())
                .isEqualTo(datastore.getEpochSecond());
        assertThat(passthrough.path("ts").path("converter").asLong())
                .isEqualTo(converter.getEpochSecond());
        // expiresOn stays a STRING. Production sends a date here, not an integer.
        assertThat(passthrough.path("ts").path("expiresOn").asText())
                .isEqualTo("2097-07-13T12:28:59.074Z");
        assertThat(passthrough.path("status").isArray()).isTrue();
        assertThat(passthrough.path("status").get(0).asText()).isEqualTo("conversion ok");
    }

    /**
     * Every fixture written before 29 Aug 2026 omits the passthrough members, and
     * ADR-003 makes both columns nullable for exactly that reason. A record that
     * carries none of them must leave NULL rather than an empty object.
     */
    @Test
    void leavesPassthroughNullWhenTheRecordCarriesNoneOfIt() {
        StatsEnvelope record =
                new StatsEnvelope(
                        "no-passthrough",
                        "tenant-a",
                        "management-parent",
                        "A1B2C3D4E5F6",
                        StatType.LORASTATS,
                        null,
                        new Timestamps(DEVICE_TIME, DEVICE_TIME, null, null, null),
                        completeData(0),
                        null);

        assertThat(writer.write(List.of(record)))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);

        Map<String, Object> stored =
                jdbc.queryForMap(
                        "SELECT record_version, passthrough, data_extra FROM lorastats "
                                + "WHERE ts_device = ? AND uid = ?",
                        Timestamp.from(DEVICE_TIME),
                        "no-passthrough");

        assertThat(stored)
                .containsEntry("record_version", null)
                .containsEntry("passthrough", null)
                .containsEntry("data_extra", null);
    }

    /**
     * D14b. The lorastats schema is {@code additionalProperties: true}, so a member
     * Heimdal adds between releases is VALID and must be preserved -- typed columns
     * have nowhere to put it, and dropping it is what ADR-003 D2 rejected in writing
     * when it chose a jsonb interface map for netstats. The counter is what makes the
     * drift visible instead of silent.
     */
    @Test
    void preservesAndMetersUnknownDataMembers() {
        double before = counter(LorastatsBatchWriter.UNMAPPED_DATA_METRIC);
        ObjectNode data = completeData(0);
        data.put("rx_future_counter", 4242L);
        data.put("lora_experimental_flag", "on");
        StatsEnvelope record = envelope("unknown-members", "tenant-a", data);

        assertThat(writer.write(List.of(record)))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);

        JsonNode extra =
                readJson(
                        jdbc.queryForObject(
                                "SELECT data_extra FROM lorastats WHERE ts_device = ? AND uid = ?",
                                Object.class,
                                Timestamp.from(DEVICE_TIME),
                                "unknown-members"));

        assertThat(extra.path("rx_future_counter").asLong()).isEqualTo(4242L);
        assertThat(extra.path("lora_experimental_flag").asText()).isEqualTo("on");
        // Only the UNMAPPED members belong here; the schema's own fields have columns.
        assertThat(extra.properties()).hasSize(2);
        assertThat(counter(LorastatsBatchWriter.UNMAPPED_DATA_METRIC)).isEqualTo(before + 1.0d);
    }

    /** A failed write is metered, so a wedged consumer is visible before it runs out of slots. */
    @Test
    void metersAFailedWrite() {
        double before = counter(LorastatsBatchWriter.WRITE_FAILED_METRIC);
        ObjectNode missingRequired = completeData(0);
        missingRequired.remove("tx_success");

        assertThat(writer.write(List.of(envelope("failed-write", "tenant-a", missingRequired))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.FAILED);

        assertThat(counter(LorastatsBatchWriter.WRITE_FAILED_METRIC)).isEqualTo(before + 1.0d);
    }

    private JsonNode readJson(Object columnValue) {
        assertThat(columnValue).isNotNull();
        try {
            return objectMapper.readTree(columnValue.toString());
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new AssertionError("stored column is not valid JSON", failure);
        }
    }

    private StatsEnvelope envelope(String uid, String tenant, ObjectNode data) {
        return new StatsEnvelope(
                uid,
                tenant,
                "management-parent",
                "A1B2C3D4E5F6",
                StatType.LORASTATS,
                1,
                new Timestamps(DEVICE_TIME, DEVICE_TIME, null, null, null),
                data,
                null);
    }

    private ObjectNode completeData(long base) {
        ObjectNode data = objectMapper.createObjectNode();
        String[] fields = {
            "rx_nr_pkts", "rx_crc_missing", "rx_crc_bad", "rx_short_pkt",
            "tx_success", "tx_failed", "tx_peek", "tx_peek_index_zero",
            "tx_override", "tx_busy", "tx_dequeue_error", "tx_type_beacon",
            "chip_comm_error", "util_fopen_failed", "fsk_received", "rx_type_arol",
            "timespan"
        };
        for (int index = 0; index < fields.length; index++) {
            data.put(fields[index], base + index + 1);
        }
        return data;
    }

    private double duplicateCount() {
        return counter(LorastatsBatchWriter.DUPLICATE_METRIC);
    }

    private double counter(String name) {
        return meterRegistry
                .get(name)
                .tag("type", StatType.LORASTATS.wireName())
                .counter()
                .count();
    }
}
