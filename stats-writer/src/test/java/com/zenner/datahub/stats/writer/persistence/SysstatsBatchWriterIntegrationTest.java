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
 * Real PostgreSQL proof of the sysstats writer: the per-type parse, the
 * {@code timespan} string-to-{@code BIGINT} range check, the {@code data_extra} residue,
 * the passthrough projection, canonical {@code clsbox} and idempotency.
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
class SysstatsBatchWriterIntegrationTest {

    private static final String TEST_PARTITION = "sysstats_t035_test_day";
    private static final Instant DEVICE_TIME = Instant.parse("2097-08-03T12:00:00Z");

    @Autowired private SysstatsBatchWriter writer;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private FailureClassifier classifier;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    void createPartition() {
        jdbc.execute(
                """
                CREATE TABLE %s PARTITION OF sysstats
                FOR VALUES FROM ('2097-08-03 00:00:00+00') TO ('2097-08-04 00:00:00+00')
                """.formatted(TEST_PARTITION));
    }

    @AfterAll
    void dropPartition() {
        jdbc.execute("DROP TABLE IF EXISTS " + TEST_PARTITION);
    }

    /** The unmodified 29 Aug production record with every optional member present. */
    @Test
    void mapsTheFullProductionRecordIntoTypedColumns() throws Exception {
        StatsEnvelope record = fixture("valid/sysstats-production-full.json", "full-production");

        assertThat(writer.write(List.of(admitted(record))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);

        Map<String, Object> stored =
                jdbc.queryForMap(
                        """
                        SELECT schema_version, memfree, uptime, idletime, load1, load5, load15,
                               runable, temp, rtt, wan_if, rebootcause, timespan, data_extra
                        FROM sysstats WHERE ts_device = ? AND uid = ?
                        """,
                        Timestamp.from(DEVICE_TIME),
                        "full-production");

        assertThat(((Number) stored.get("schema_version")).intValue()).isEqualTo(1);
        assertThat(stored)
                .containsEntry("memfree", 372952L)
                .containsEntry("uptime", 104187.93d)
                .containsEntry("idletime", 306486.74d)
                .containsEntry("load1", 1.32d)
                .containsEntry("load5", 1.29d)
                .containsEntry("load15", 1.34d)
                .containsEntry("runable", "2/160")
                .containsEntry("temp", 60573L)
                .containsEntry("rtt", 0L)
                .containsEntry("wan_if", "wwan")
                .containsEntry("rebootcause", "reboot")
                // The STRING "300" on the wire is the bigint 300 in the column.
                .containsEntry("timespan", 300L);
        assertThat(stored.get("data_extra")).as("the payload is exactly the schema").isNull();
    }

    /** A gateway with no temperature sensor: the five optional members are NULL, not 0. */
    @Test
    void storesNullForTheOptionalMembersAGatewayOmits() throws Exception {
        StatsEnvelope record = fixture("valid/sysstats-production-minimal.json", "minimal-production");

        assertThat(writer.write(List.of(admitted(record))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);

        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT count(*) FROM sysstats
                                WHERE ts_device = ? AND uid = ?
                                  AND temp IS NULL AND rtt IS NULL AND wan_if IS NULL
                                  AND rebootcause IS NULL AND timespan IS NULL
                                  AND memfree = 70916 AND runable = '1/85'
                                """,
                                Integer.class,
                                Timestamp.from(DEVICE_TIME),
                                "minimal-production"))
                .isEqualTo(1);
    }

    /**
     * The range check, the trap for this type. The schema caps {@code timespan}
     * at nineteen digits and so admits {@code 9223372036854775808}; the column cannot
     * hold it. The record is refused before any conversion and nothing is written — the
     * alternative, a wrapped or truncated value, is a row that is wrong rather than
     * absent, and no test would ever see it.
     */
    @Test
    void refusesATimespanThatOverflowsBigintAndWritesNothing() {
        ObjectNode data = completeData();
        data.put("timespan", "9223372036854775808");
        double unmappableBefore = payloadRejected(WriterGateMetrics.REASON_UNMAPPABLE);
        double writeFailedBefore = counter(LorastatsBatchWriter.WRITE_FAILED_METRIC);

        List<RecordWriteResult> results =
                writer.write(List.of(admitted(envelope("timespan-overflow", "tenant-a", data))));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(RecordWriteStatus.FAILED);
            Throwable failure = result.failureOptional().orElseThrow();
            assertThat(failure).isInstanceOf(PermanentFailureException.class)
                    .hasMessageContaining("timespan");
            // Permanent: it skips the three retry tiers and goes straight to the DLQ.
            assertThat(classifier.isPermanent(failure)).isTrue();
        });
        assertThat(storedCount("timespan-overflow")).isZero();
        // Counted as a payload nobody can map, NOT as a database write failure.
        assertThat(payloadRejected(WriterGateMetrics.REASON_UNMAPPABLE)).isEqualTo(unmappableBefore + 1.0d);
        assertThat(counter(LorastatsBatchWriter.WRITE_FAILED_METRIC)).isEqualTo(writeFailedBefore);
    }

    @Test
    void acceptsTheBigintMaximumAsATimespanString() {
        ObjectNode data = completeData();
        data.put("timespan", "9223372036854775807");

        assertThat(writer.write(List.of(admitted(envelope("timespan-max", "tenant-a", data)))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT timespan FROM sysstats WHERE ts_device = ? AND uid = ?",
                                Long.class,
                                Timestamp.from(DEVICE_TIME),
                                "timespan-max"))
                .isEqualTo(Long.MAX_VALUE);
    }

    /** A number where the wire says string is a mapping failure, never a silent cast. */
    @Test
    void refusesATimespanSentAsAnIntegerRatherThanGuessing() {
        ObjectNode data = completeData();
        data.put("timespan", 300);

        List<RecordWriteResult> results =
                writer.write(List.of(admitted(envelope("timespan-integer", "tenant-a", data))));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(RecordWriteStatus.FAILED);
            assertThat(result.failureOptional().orElseThrow())
                    .isInstanceOf(PermanentFailureException.class);
        });
        assertThat(storedCount("timespan-integer")).isZero();
    }

    @Test
    void suppressesAndMetersThePartitionKeyDuplicateEvenAcrossTenants() {
        double duplicatesBefore = counter(LorastatsBatchWriter.DUPLICATE_METRIC);
        StatsEnvelope first = envelope("duplicate-proof", "tenant-a", completeData());
        // Same (ts_device, uid), a different tenant: tenant is deliberately NOT in the
        // key, and this counter is what would reveal a cross-tenant collision.
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
        data.put("cpu_temp_future", 41);
        data.putObject("nested").put("kept", true);

        assertThat(writer.write(List.of(admitted(envelope("extra-members", "tenant-a", data)))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);

        JsonNode extra =
                readJson(
                        jdbc.queryForObject(
                                "SELECT data_extra FROM sysstats WHERE ts_device = ? AND uid = ?",
                                Object.class,
                                Timestamp.from(DEVICE_TIME),
                                "extra-members"));
        assertThat(extra.path("cpu_temp_future").asInt()).isEqualTo(41);
        assertThat(extra.path("nested").path("kept").asBoolean()).isTrue();
        // Mapped members do NOT leak into the residue.
        assertThat(extra.has("memfree")).isFalse();
        assertThat(counter(LorastatsBatchWriter.UNMAPPED_DATA_METRIC)).isEqualTo(unmappedBefore + 1.0d);
    }

    /** The live shape: meta and ts.deviceOrig survive the round trip losslessly. */
    @Test
    void preservesThePassthroughMembersHeimdalActuallySends() throws Exception {
        StatsEnvelope record =
                fixture("valid/sysstats-production-ts-device-corrected.json", "ts-device-corrected");

        assertThat(writer.write(List.of(admitted(record))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);

        Map<String, Object> stored =
                jdbc.queryForMap(
                        "SELECT record_version, tenant, maintenant, passthrough FROM sysstats "
                                + "WHERE ts_device = ? AND uid = ?",
                        Timestamp.from(DEVICE_TIME),
                        "ts-device-corrected");
        JsonNode passthrough = readJson(stored.get("passthrough"));

        assertThat(((Number) stored.get("record_version")).intValue()).isEqualTo(1);
        // The minol pattern, stored exactly as stamped: tenant is the isolation field and
        // maintenant differs from it on this tenant.
        assertThat(stored).containsEntry("tenant", "minol-gateway").containsEntry("maintenant", "minol");
        assertThat(passthrough.path("meta").path("tsDeviceCorrected").asBoolean()).isTrue();
        assertThat(passthrough.path("ts").path("deviceOrig").asLong()).isEqualTo(1_786_616_989L);
        assertThat(passthrough.path("ts").path("datastore").asLong()).isEqualTo(1_786_617_001L);
        assertThat(passthrough.path("ts").path("expiresOn").asText()).isEqualTo("2026-08-13T10:30:01.3Z");
        assertThat(passthrough.path("status").get(0).asText()).isEqualTo("conversion ok");
    }

    @Test
    void storesOneCanonicalClsboxCaseSoTheDeviceCannotSplitAcrossTwoKeys() {
        StatsEnvelope lowercase = envelope("clsbox-lower", "tenant-a", "a1b2c3d4e5f6", completeData());

        assertThat(writer.write(List.of(admitted(lowercase))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT clsbox FROM sysstats WHERE ts_device = ? AND uid = ?",
                                String.class,
                                Timestamp.from(DEVICE_TIME),
                                "clsbox-lower"))
                .isEqualTo("A1B2C3D4E5F6");
    }

    /**
     * A stored record rebuilds into the record that was received, member for member and
     * value for value — the promise the API will make.
     */
    @Test
    void aStoredRecordRebuildsIntoTheRecordThatWasReceived() throws Exception {
        ObjectNode received = objectMapper.createObjectNode();
        received.put("uid", "round-trip-proof");
        received.put("tenant", "tenant-a");
        received.put("maintenant", "management-parent");
        received.put("clsbox", "A1B2C3D4E5F6");
        received.put("type", "sysstats");
        received.put("version", 1);
        ObjectNode ts = received.putObject("ts");
        ts.put("server", DEVICE_TIME.getEpochSecond());
        ts.put("device", DEVICE_TIME.getEpochSecond());
        ts.put("datastore", DEVICE_TIME.getEpochSecond() + 1);
        ts.put("converter", DEVICE_TIME.getEpochSecond() + 2);
        ts.put("expiresOn", "2097-08-04T12:00:00.000Z");
        ObjectNode data = completeData();
        data.put("temp", 60573);
        data.put("rtt", -1);
        data.put("wan_if", "wwan");
        data.put("rebootcause", "reboot");
        data.put("timespan", "300");
        data.put("future_member", "kept");
        received.set("data", data);
        received.putArray("status").add("conversion ok");

        StatsEnvelope record = objectMapper.treeToValue(received, StatsEnvelope.class);
        assertThat(writer.write(List.of(admitted(record))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT * FROM sysstats WHERE ts_device = ? AND uid = ?",
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
        rebuilt.put("type", StatType.SYSSTATS.wireName());
        rebuilt.put("version", ((Number) row.get("record_version")).intValue());

        ObjectNode ts = rebuilt.putObject("ts");
        ts.put("server", ((Timestamp) row.get("ts_server")).toInstant().getEpochSecond());
        ts.put("device", ((Timestamp) row.get("ts_device")).toInstant().getEpochSecond());
        JsonNode passthrough = readJson(row.get("passthrough"));
        passthrough.path("ts").properties().forEach(entry -> ts.set(entry.getKey(), entry.getValue()));
        rebuilt.set("status", passthrough.get("status"));

        ObjectNode data = rebuilt.putObject("data");
        for (String field : List.of("memfree", "temp", "rtt")) {
            Object value = row.get(field);
            if (value != null) {
                data.put(field, ((Number) value).longValue());
            }
        }
        for (String field : List.of("uptime", "idletime", "load1", "load5", "load15")) {
            data.put(field, ((Number) row.get(field)).doubleValue());
        }
        for (String field : List.of("runable", "wan_if", "rebootcause")) {
            Object value = row.get(field);
            if (value != null) {
                data.put(field, (String) value);
            }
        }
        Object timespan = row.get("timespan");
        if (timespan != null) {
            // Back to the STRING the wire sends for this type.
            data.put("timespan", Long.toString(((Number) timespan).longValue()));
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
                StatType.SYSSTATS,
                1,
                new Timestamps(DEVICE_TIME, DEVICE_TIME, null, null, null, null),
                data,
                null,
                null);
    }

    private ObjectNode completeData() {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("memfree", 56812);
        data.put("uptime", 955641.36);
        data.put("idletime", 3632478.13);
        data.put("load1", 0.41);
        data.put("load5", 0.42);
        data.put("load15", 0.38);
        data.put("runable", "2/139");
        return data;
    }

    private int storedCount(String uid) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM sysstats WHERE ts_device = ? AND uid = ?",
                Integer.class,
                Timestamp.from(DEVICE_TIME),
                uid);
    }

    private double counter(String name) {
        return meterRegistry.get(name).tag("type", StatType.SYSSTATS.wireName()).counter().count();
    }

    private double payloadRejected(String reason) {
        Counter counter =
                meterRegistry
                        .find(WriterGateMetrics.PAYLOAD_REJECTED)
                        .tag("type", StatType.SYSSTATS.wireName())
                        .tag("reason", reason)
                        .counter();
        return counter == null ? 0.0d : counter.count();
    }
}
