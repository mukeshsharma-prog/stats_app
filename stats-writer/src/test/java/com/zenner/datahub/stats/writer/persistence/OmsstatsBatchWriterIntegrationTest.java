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
import com.zenner.datahub.stats.writer.retry.FailureClassifier;
import com.zenner.datahub.stats.writer.retry.PermanentFailureException;
import com.zenner.datahub.stats.writer.validation.ValidatedRecord;
import com.zenner.datahub.stats.writer.validation.WriterGateMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.InputStream;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real PostgreSQL proof of the omsstats writer (T-036): the per-type parse ADR-003 calls
 * for, the integer-to-{@code BIGINT} range check on a type with no string coercion, the
 * D14b residue, the passthrough projection — {@code ts.converter} preserved and not acted
 * on (ADR-004) — canonical {@code clsbox} and idempotency.
 */
@SpringBootTest(
        classes = StatsWriterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            // A type with no consumer, so no listener attaches to the shared broker.
            "stats.writer.stat-type=netstats",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "stats.retention.schedule=-",
            "stats.retention.pre-create-on-startup=false"
        })
@Import(PostgresSupport.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OmsstatsBatchWriterIntegrationTest {

    private static final String TEST_PARTITION = "omsstats_t036_test_day";
    private static final Instant DEVICE_TIME = Instant.parse("2097-09-03T12:00:00Z");

    @Autowired private OmsstatsBatchWriter writer;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private FailureClassifier classifier;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    void createPartition() {
        jdbc.execute(
                """
                CREATE TABLE %s PARTITION OF omsstats
                FOR VALUES FROM ('2097-09-03 00:00:00+00') TO ('2097-09-04 00:00:00+00')
                """.formatted(TEST_PARTITION));
    }

    @AfterAll
    void dropPartition() {
        jdbc.execute("DROP TABLE IF EXISTS " + TEST_PARTITION);
    }

    /** The unmodified 29 Aug production record — the one type SRS v1.0 got right. */
    @Test
    void mapsTheProductionRecordIntoTypedColumns() throws Exception {
        StatsEnvelope record = fixture("valid/omsstats-production.json", "production");

        assertThat(writer.write(List.of(admitted(record))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);

        Map<String, Object> stored =
                jdbc.queryForMap(
                        """
                        SELECT schema_version, rx_nr_pkts, rx_valid_nr_pkts, rx_crc_bad,
                               device_count, timespan, data_extra
                        FROM omsstats WHERE ts_device = ? AND uid = ?
                        """,
                        Timestamp.from(DEVICE_TIME),
                        "production");

        assertThat(((Number) stored.get("schema_version")).intValue()).isEqualTo(1);
        assertThat(stored)
                .containsEntry("rx_nr_pkts", 663L)
                .containsEntry("rx_valid_nr_pkts", 105L)
                .containsEntry("rx_crc_bad", 528L)
                .containsEntry("device_count", 16L)
                // An INTEGER on the wire for this type, unlike sysstats' "300".
                .containsEntry("timespan", 1800L);
        assertThat(stored.get("data_extra")).as("the payload is exactly the schema").isNull();
    }

    /**
     * ADR-004 as amended by ADR-007 §2: {@code ts.converter} is on every production omsstats
     * record. It is preserved in {@code passthrough} in the wire's own units and nothing
     * derives from it — {@code ts_server} and {@code ts_device} are exactly what arrived.
     */
    @Test
    void preservesTsConverterWithoutNormalisingAnyTimestamp() throws Exception {
        StatsEnvelope record = fixture("valid/omsstats-ts-converter-present.json", "ts-converter");

        assertThat(writer.write(List.of(admitted(record))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);

        Map<String, Object> stored =
                jdbc.queryForMap(
                        "SELECT ts_server, ts_device, passthrough FROM omsstats WHERE ts_device = ? AND uid = ?",
                        Timestamp.from(DEVICE_TIME),
                        "ts-converter");
        JsonNode passthrough = readJson(stored.get("passthrough"));

        // The fixture's ts.server is 1787054120 and its ts.converter equals it; ts.device
        // is re-keyed to this test's partition day. Neither stored clock moved toward the
        // converter value: the columns hold the wire values, second for second.
        assertThat(((Timestamp) stored.get("ts_server")).toInstant().getEpochSecond()).isEqualTo(1_787_054_120L);
        assertThat(((Timestamp) stored.get("ts_device")).toInstant()).isEqualTo(DEVICE_TIME);
        assertThat(passthrough.path("ts").path("converter").asLong()).isEqualTo(1_787_054_120L);
        assertThat(passthrough.path("ts").path("datastore").asLong()).isEqualTo(1_787_054_120L);
        assertThat(passthrough.path("ts").path("expiresOn").asText()).isEqualTo("2026-08-27T12:25:37.071Z");
        assertThat(passthrough.path("status").get(0).asText()).isEqualTo("conversion ok");
    }

    /** DL-146's other spelling: {@code expiresOn} as Extended JSON survives verbatim. */
    @Test
    void preservesExpiresOnInTheExtendedJsonFormTheDatastoreReturns() throws Exception {
        StatsEnvelope record =
                fixture("valid/omsstats-expires-on-extended-json.json", "expires-on-extended");

        assertThat(writer.write(List.of(admitted(record))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);

        JsonNode passthrough =
                readJson(
                        jdbc.queryForObject(
                                "SELECT passthrough FROM omsstats WHERE ts_device = ? AND uid = ?",
                                Object.class,
                                Timestamp.from(DEVICE_TIME),
                                "expires-on-extended"));
        assertThat(passthrough.path("ts").path("expiresOn").path("$date").asText())
                .isEqualTo("2026-08-14T15:14:41.124Z");
    }

    /**
     * STA-04C on a type with no string coercion. The schema types every member as
     * {@code integer} with no maximum, and JSON Schema's integer has no width, so
     * {@code 9223372036854775808} is schema-valid; the column cannot hold it. The record is
     * refused before any conversion and nothing is written — the alternative, a wrapped or
     * truncated value, is a row that is wrong rather than absent, and no test would see it.
     */
    @Test
    void refusesACounterThatOverflowsBigintAndWritesNothing() throws Exception {
        StatsEnvelope record = fixture("valid/omsstats-counter-overflow.json", "counter-overflow");
        // The typed view held the literal without wrapping it: that is what makes the check possible.
        assertThat(record.data().get("rx_nr_pkts").bigIntegerValue())
                .isEqualTo(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));
        double unmappableBefore = payloadRejected(WriterGateMetrics.REASON_UNMAPPABLE);
        double writeFailedBefore = counter(LorastatsBatchWriter.WRITE_FAILED_METRIC);

        List<RecordWriteResult> results = writer.write(List.of(admitted(record)));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(RecordWriteStatus.FAILED);
            Throwable failure = result.failureOptional().orElseThrow();
            assertThat(failure).isInstanceOf(PermanentFailureException.class)
                    .hasMessageContaining("rx_nr_pkts");
            // Permanent: it skips the three retry tiers and goes straight to the DLQ.
            assertThat(classifier.isPermanent(failure)).isTrue();
        });
        assertThat(storedCount("counter-overflow")).isZero();
        // Counted as a payload nobody can map, NOT as a database write failure (DL-126).
        assertThat(payloadRejected(WriterGateMetrics.REASON_UNMAPPABLE)).isEqualTo(unmappableBefore + 1.0d);
        assertThat(counter(LorastatsBatchWriter.WRITE_FAILED_METRIC)).isEqualTo(writeFailedBefore);
    }

    @Test
    void acceptsTheBigintMaximumAsACounter() {
        ObjectNode data = completeData();
        data.put("rx_nr_pkts", Long.MAX_VALUE);

        assertThat(writer.write(List.of(admitted(envelope("counter-max", "tenant-a", data)))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT rx_nr_pkts FROM omsstats WHERE ts_device = ? AND uid = ?",
                                Long.class,
                                Timestamp.from(DEVICE_TIME),
                                "counter-max"))
                .isEqualTo(Long.MAX_VALUE);
    }

    /** A string where the wire says integer is a mapping failure, never a silent parse. */
    @Test
    void refusesATimespanSentAsAStringRatherThanGuessing() {
        ObjectNode data = completeData();
        data.put("timespan", "300");

        List<RecordWriteResult> results =
                writer.write(List.of(admitted(envelope("timespan-string", "tenant-a", data))));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(RecordWriteStatus.FAILED);
            assertThat(result.failureOptional().orElseThrow())
                    .isInstanceOf(PermanentFailureException.class)
                    .hasMessageContaining("timespan");
        });
        assertThat(storedCount("timespan-string")).isZero();
    }

    @Test
    void suppressesAndMetersThePartitionKeyDuplicateEvenAcrossTenants() {
        double duplicatesBefore = counter(LorastatsBatchWriter.DUPLICATE_METRIC);
        StatsEnvelope first = envelope("duplicate-proof", "tenant-a", completeData());
        // Same (ts_device, uid), a different tenant: tenant is deliberately NOT in the
        // key (ADR-003), and this counter is what would reveal a cross-tenant collision.
        StatsEnvelope second = envelope("duplicate-proof", "tenant-b", completeData());

        assertThat(writer.write(List.of(admitted(first), admitted(second))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED, RecordWriteStatus.DUPLICATE_SUPPRESSED);
        assertThat(storedCount("duplicate-proof")).isEqualTo(1);
        assertThat(counter(LorastatsBatchWriter.DUPLICATE_METRIC)).isEqualTo(duplicatesBefore + 1.0d);
    }

    @Test
    void preservesAndMetersUnknownDataMembers() throws Exception {
        double unmappedBefore = counter(LorastatsBatchWriter.UNMAPPED_DATA_METRIC);
        ObjectNode data = completeData();
        data.put("rx_future_counter", 41);
        data.putObject("nested").put("kept", true);

        assertThat(writer.write(List.of(admitted(envelope("extra-members", "tenant-a", data)))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);

        JsonNode extra =
                readJson(
                        jdbc.queryForObject(
                                "SELECT data_extra FROM omsstats WHERE ts_device = ? AND uid = ?",
                                Object.class,
                                Timestamp.from(DEVICE_TIME),
                                "extra-members"));
        assertThat(extra.path("rx_future_counter").asInt()).isEqualTo(41);
        assertThat(extra.path("nested").path("kept").asBoolean()).isTrue();
        // Mapped members do NOT leak into the residue.
        assertThat(extra.has("rx_nr_pkts")).isFalse();
        assertThat(counter(LorastatsBatchWriter.UNMAPPED_DATA_METRIC)).isEqualTo(unmappedBefore + 1.0d);
    }

    @Test
    void storesOneCanonicalClsboxCaseSoTheDeviceCannotSplitAcrossTwoKeys() {
        StatsEnvelope lowercase = envelope("clsbox-lower", "tenant-a", "a1b2c3d4e5f6", completeData());

        assertThat(writer.write(List.of(admitted(lowercase))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT clsbox FROM omsstats WHERE ts_device = ? AND uid = ?",
                                String.class,
                                Timestamp.from(DEVICE_TIME),
                                "clsbox-lower"))
                .isEqualTo("A1B2C3D4E5F6");
    }

    /**
     * A stored record rebuilds into the record that was received, member for member and
     * value for value — the promise the API will make (ADR-003 D2, ADR-007 §2).
     */
    @Test
    void aStoredRecordRebuildsIntoTheRecordThatWasReceived() throws Exception {
        ObjectNode received = objectMapper.createObjectNode();
        received.put("uid", "round-trip-proof");
        received.put("tenant", "tenant-a");
        received.put("maintenant", "management-parent");
        received.put("clsbox", "A1B2C3D4E5F6");
        received.put("type", "omsstats");
        received.put("version", 1);
        ObjectNode ts = received.putObject("ts");
        ts.put("server", DEVICE_TIME.getEpochSecond());
        ts.put("device", DEVICE_TIME.getEpochSecond());
        ts.put("datastore", DEVICE_TIME.getEpochSecond() + 1);
        ts.put("converter", DEVICE_TIME.getEpochSecond() + 2);
        ts.put("expiresOn", "2097-09-04T12:00:00.000Z");
        ObjectNode data = completeData();
        data.put("future_member", "kept");
        received.set("data", data);
        received.putArray("status").add("conversion ok");

        StatsEnvelope record = objectMapper.treeToValue(received, StatsEnvelope.class);
        assertThat(writer.write(List.of(admitted(record))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT * FROM omsstats WHERE ts_device = ? AND uid = ?",
                        Timestamp.from(DEVICE_TIME),
                        "round-trip-proof");

        assertThat(rebuild(row).equals(VALUE_EQUALITY, received))
                .as("rebuilt %s%nreceived %s", rebuild(row), received)
                .isTrue();
    }

    private static final Comparator<JsonNode> VALUE_EQUALITY =
            (left, right) -> {
                if (left.isNumber() && right.isNumber()) {
                    return left.decimalValue().compareTo(right.decimalValue());
                }
                return left.equals(right) ? 0 : 1;
            };

    /** Rebuilds the received JSON from the stored columns, the way the API will. */
    private ObjectNode rebuild(Map<String, Object> row) {
        ObjectNode rebuilt = objectMapper.createObjectNode();
        rebuilt.put("uid", (String) row.get("uid"));
        rebuilt.put("tenant", (String) row.get("tenant"));
        rebuilt.put("maintenant", (String) row.get("maintenant"));
        rebuilt.put("clsbox", (String) row.get("clsbox"));
        rebuilt.put("type", StatType.OMSSTATS.wireName());
        rebuilt.put("version", ((Number) row.get("record_version")).intValue());

        ObjectNode ts = rebuilt.putObject("ts");
        ts.put("server", ((Timestamp) row.get("ts_server")).toInstant().getEpochSecond());
        ts.put("device", ((Timestamp) row.get("ts_device")).toInstant().getEpochSecond());
        JsonNode passthrough = readJson(row.get("passthrough"));
        passthrough.path("ts").properties().forEach(entry -> ts.set(entry.getKey(), entry.getValue()));
        rebuilt.set("status", passthrough.get("status"));

        ObjectNode data = rebuilt.putObject("data");
        for (String field : OmsstatsBatchWriter.REQUIRED_FIELDS) {
            data.put(field, ((Number) row.get(field)).longValue());
        }
        readJson(row.get("data_extra")).properties()
                .forEach(entry -> data.set(entry.getKey(), entry.getValue()));
        return rebuilt;
    }

    // ---- helpers -----------------------------------------------------------

    private JsonNode readJson(Object columnValue) {
        assertThat(columnValue).isNotNull();
        try {
            return objectMapper.readTree(columnValue.toString());
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new AssertionError("stored column is not valid JSON", failure);
        }
    }

    private static ValidatedRecord admitted(StatsEnvelope envelope) {
        return ValidatedRecord.of(envelope, "v1");
    }

    /**
     * A normative fixture, re-keyed onto this test's partition day and uid so the
     * production values are byte-faithful and the row is findable.
     */
    private StatsEnvelope fixture(String name, String uid) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            ObjectNode record = (ObjectNode) objectMapper.readTree(Objects.requireNonNull(in, name));
            record.put("uid", uid);
            ((ObjectNode) record.get("ts")).put("device", DEVICE_TIME.getEpochSecond());
            return objectMapper.treeToValue(record, StatsEnvelope.class);
        }
    }

    private StatsEnvelope envelope(String uid, String tenant, ObjectNode data) {
        return envelope(uid, tenant, "A1B2C3D4E5F6", data);
    }

    private StatsEnvelope envelope(String uid, String tenant, String clsbox, ObjectNode data) {
        return new StatsEnvelope(
                uid,
                tenant,
                "management-parent",
                clsbox,
                StatType.OMSSTATS,
                1,
                new Timestamps(DEVICE_TIME, DEVICE_TIME, null, null, null, null),
                data,
                null,
                null);
    }

    private ObjectNode completeData() {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("rx_nr_pkts", 663);
        data.put("rx_valid_nr_pkts", 105);
        data.put("rx_crc_bad", 528);
        data.put("device_count", 16);
        data.put("timespan", 1800);
        return data;
    }

    private int storedCount(String uid) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM omsstats WHERE ts_device = ? AND uid = ?",
                Integer.class,
                Timestamp.from(DEVICE_TIME),
                uid);
    }

    private double counter(String name) {
        return meterRegistry.get(name).tag("type", StatType.OMSSTATS.wireName()).counter().count();
    }

    private double payloadRejected(String reason) {
        Counter counter =
                meterRegistry
                        .find(WriterGateMetrics.PAYLOAD_REJECTED)
                        .tag("type", StatType.OMSSTATS.wireName())
                        .tag("reason", reason)
                        .counter();
        return counter == null ? 0.0d : counter.count();
    }
}
