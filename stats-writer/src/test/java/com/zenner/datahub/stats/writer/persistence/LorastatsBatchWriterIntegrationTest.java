package com.zenner.datahub.stats.writer.persistence;

import static org.assertj.core.api.Assertions.assertThat;

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
            "stats.retention.schedule=-"
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
    void mapsEveryV1PayloadColumnAndLeavesT058ColumnsNull() {
        StatsEnvelope record = envelope("all-columns", "tenant-a", completeData(10));

        assertThat(writer.write(List.of(record)))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);

        java.util.Map<String, Object> stored =
                jdbc.queryForMap(
                        """
                        SELECT schema_version, record_version, passthrough,
                               rx_nr_pkts, rx_short_pkt, fsk_received,
                               rx_type_arol, timespan
                        FROM lorastats WHERE ts_device = ? AND uid = ?
                        """,
                        Timestamp.from(DEVICE_TIME),
                        "all-columns");

        assertThat(((Number) stored.get("schema_version")).intValue()).isEqualTo(1);
        assertThat(stored)
                .containsEntry("record_version", null)
                .containsEntry("passthrough", null)
                .containsEntry("rx_nr_pkts", 11L)
                .containsEntry("rx_short_pkt", 14L)
                .containsEntry("fsk_received", 25L)
                .containsEntry("rx_type_arol", 26L)
                .containsEntry("timespan", 27L);
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
        return meterRegistry
                .get(LorastatsBatchWriter.DUPLICATE_METRIC)
                .tag("type", StatType.LORASTATS.wireName())
                .counter()
                .count();
    }
}
