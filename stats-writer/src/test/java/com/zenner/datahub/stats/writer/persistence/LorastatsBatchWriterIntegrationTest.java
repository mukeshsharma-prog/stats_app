package com.zenner.datahub.stats.writer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.common.model.StatsEnvelope;
import com.zenner.datahub.stats.common.model.Timestamps;
import com.zenner.datahub.stats.testsupport.PostgresSupport;
import com.zenner.datahub.stats.writer.StatsWriterApplication;
import com.zenner.datahub.stats.writer.retry.FailureClassifier;
import com.zenner.datahub.stats.writer.retry.PermanentFailureException;
import com.zenner.datahub.stats.writer.validation.ValidatedRecord;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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

/** Real PostgreSQL proof of per-record precision and idempotency. */
@SpringBootTest(
        classes = StatsWriterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "stats.writer.stat-type=netstats",
            "spring.rabbitmq.listener.simple.auto-startup=false",
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

    @Autowired
    private FailureClassifier classifier;

    @Autowired
    private com.zenner.datahub.stats.writer.validation.WriterGateMetrics gateMetrics;

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

    /**
     * Per-record precision: one poison record inside a list is attributed to its
     * own index and the other ninety-nine still commit.
     *
     * <p>A payload missing a required counter is refused by the dialect
     * detector rather than by the column binding, because "which dialect is this" is
     * answered by which required key-set is present and an incomplete one answers
     * nothing. The field-level diagnosis is not lost, it has MOVED: in production the
     * record never reaches this class, because {@code PayloadGate} refuses it first with
     * {@code /data/tx_success: missing}. This test reaches the writer directly, which is
     * why it sees the detector's answer instead.</p>
     */
    @Test
    void attributesOneBadRecordWithinOneHundredAndCommitsTheOtherNinetyNine() {
        List<ValidatedRecord> records = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            ObjectNode data = completeData(index);
            if (index == 37) {
                data.remove("tx_success");
            }
            records.add(admitted(envelope("batch-" + index, "tenant-a", data)));
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
                                    .isInstanceOf(PermanentFailureException.class)
                                    .hasMessageContaining("no known payload dialect");
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
        double persistedBefore = counter(LorastatsBatchWriter.PERSISTED_METRIC);

        assertThat(writer.write(List.of(admitted(original))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);
        assertThat(writer.write(List.of(admitted(collision))))
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
        assertThat(counter(LorastatsBatchWriter.PERSISTED_METRIC)).isEqualTo(persistedBefore + 1);
    }

    @Test
    void mapsEveryV1PayloadColumn() {
        StatsEnvelope record = envelope("all-columns", "tenant-a", completeData(10));

        assertThat(writer.write(List.of(admitted(record))))
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
     * The envelope members production sends on every
     * record are preserved and never read. Dropping them at the INSERT would make the
     * round trip lossy while every schema test still passed, because none of these
     * members is required.
     */
    @Test
    void preservesRecordVersionAndTheFivePassthroughEnvelopeMembers() {
        Instant datastore = DEVICE_TIME.plusSeconds(1);
        Instant converter = DEVICE_TIME.plusSeconds(2);
        // Heimdal rewrote ts.device and kept the gateway's own clock here.
        Instant deviceOrig = DEVICE_TIME.minusSeconds(12);
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
                                deviceOrig,
                                TextNode.valueOf("2097-07-13T12:28:59.074Z")),
                        completeData(0),
                        objectMapper.createObjectNode().put("tsDeviceCorrected", true),
                        List.of("conversion ok"));

        assertThat(writer.write(List.of(admitted(record))))
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
        // expiresOn stays a STRING when it arrived as one. Production sends a date
        // here, not an integer.
        assertThat(passthrough.path("ts").path("expiresOn").asText())
                .isEqualTo("2097-07-13T12:28:59.074Z");
        assertThat(passthrough.path("ts").path("deviceOrig").asLong())
                .isEqualTo(deviceOrig.getEpochSecond());
        assertThat(passthrough.path("meta").path("tsDeviceCorrected").asBoolean()).isTrue();
        assertThat(passthrough.path("status").isArray()).isTrue();
        assertThat(passthrough.path("status").get(0).asText()).isEqualTo("conversion ok");
    }

    /**
     * The live datastore returns {@code expiresOn} as Mongo Extended JSON,
     * {@code {"$date": "..."}}, and which form the HTTP forward emits is open.
     * Whichever arrives is what is stored: a String column would have thrown on the
     * object form, and normalising to one spelling would be the timestamp rewrite
     * the Stats App never performs.
     */
    @Test
    void storesExpiresOnInTheExtendedJsonFormWhenThatIsWhatArrived() {
        ObjectNode extended = objectMapper.createObjectNode().put("$date", "2097-07-13T12:28:59.074Z");
        StatsEnvelope record =
                new StatsEnvelope(
                        "expires-on-extended-json",
                        "tenant-a",
                        "management-parent",
                        "A1B2C3D4E5F6",
                        StatType.LORASTATS,
                        1,
                        new Timestamps(DEVICE_TIME, DEVICE_TIME, null, null, null, extended),
                        completeData(0),
                        null,
                        null);

        assertThat(writer.write(List.of(admitted(record))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);

        JsonNode passthrough =
                readJson(
                        jdbc.queryForObject(
                                "SELECT passthrough FROM lorastats WHERE ts_device = ? AND uid = ?",
                                Object.class,
                                Timestamp.from(DEVICE_TIME),
                                "expires-on-extended-json"));
        assertThat(passthrough.path("ts").path("expiresOn")).isEqualTo(extended);
    }

    /**
     * Every fixture written before 29 Aug 2026 omits the passthrough members, and
     * both columns are nullable for exactly that reason. A record that
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
                        new Timestamps(DEVICE_TIME, DEVICE_TIME, null, null, null, null),
                        completeData(0),
                        null,
                        null);

        assertThat(writer.write(List.of(admitted(record))))
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
     * The lorastats schema is {@code additionalProperties: true}, so a member
     * Heimdal adds between releases is VALID and must be preserved -- typed columns
     * have nowhere to put it, and dropping it is the silent loss the storage design rejected
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

        assertThat(writer.write(List.of(admitted(record))))
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

    /**
     * A failed write is metered, so a wedged consumer is visible before it runs out of
     * slots.
     *
     * <p>The failure used here is a <strong>real database rejection</strong> — a
     * {@code ts_device} with no partition to hold it — and that is the point of the test
     * rather than an incidental choice. It previously used a record missing a required
     * counter, which never reaches PostgreSQL at all: it fails while the row is being
     * bound. That made this counter agree with itself while meaning two different
     * things, and {@code records_write_failed} only earns its name if the write was
     * genuinely attempted.</p>
     */
    @Test
    void metersAFailedWrite() {
        double before = counter(LorastatsBatchWriter.WRITE_FAILED_METRIC);
        double unmappableBefore = rejected("unmappable");
        double databaseBefore = counter(LorastatsBatchWriter.POSTGRES_ERROR_METRIC);
        double persistedBefore = counter(LorastatsBatchWriter.PERSISTED_METRIC);

        StatsEnvelope noPartition =
                new StatsEnvelope(
                        "failed-write",
                        "tenant-a",
                        "management-parent",
                        "A1B2C3D4E5F6",
                        StatType.LORASTATS,
                        1,
                        // A day far outside every partition this class creates.
                        new Timestamps(
                                Instant.parse("2098-01-01T12:00:00Z"),
                                Instant.parse("2098-01-01T12:00:00Z"),
                                null,
                                null,
                                null,
                                null),
                        completeData(0),
                        null,
                        null);

        assertThat(writer.write(List.of(admitted(noPartition))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.FAILED);

        assertThat(counter(LorastatsBatchWriter.WRITE_FAILED_METRIC))
                .as("the INSERT was attempted and PostgreSQL refused it")
                .isEqualTo(before + 1.0d);
        assertThat(rejected("unmappable"))
                .as("...and that is not a payload the mapper could not handle")
                .isEqualTo(unmappableBefore);
        assertThat(counter(LorastatsBatchWriter.POSTGRES_ERROR_METRIC))
                .as("a missing partition is PostgreSQL's refusal, so it is a database error too")
                .isEqualTo(databaseBefore + 1.0d);
        assertThat(counter(LorastatsBatchWriter.PERSISTED_METRIC))
                .as("nothing was committed")
                .isEqualTo(persistedBefore);
    }

    /** A table-invariant rejection counts as a database error and never as persisted. */
    @Test
    void databaseConstraintFailureIsCountedWithoutClaimingPersistence() {
        double databaseBefore = counter(LorastatsBatchWriter.POSTGRES_ERROR_METRIC);
        double persistedBefore = counter(LorastatsBatchWriter.PERSISTED_METRIC);
        double failuresBefore = counter(LorastatsBatchWriter.WRITE_FAILED_METRIC);

        StatsEnvelope badClsbox =
                new StatsEnvelope(
                        "metrics-check-failure",
                        "tenant-a",
                        "management-parent",
                        // Not 12 hex characters: refused by the table's own CHECK.
                        "INVALID",
                        StatType.LORASTATS,
                        1,
                        new Timestamps(Instant.now(), Instant.now(), null, null, null, null),
                        completeData(0),
                        null,
                        null);

        assertThat(writer.write(List.of(admitted(badClsbox))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.FAILED);

        assertThat(counter(LorastatsBatchWriter.POSTGRES_ERROR_METRIC)).isEqualTo(databaseBefore + 1);
        assertThat(counter(LorastatsBatchWriter.PERSISTED_METRIC)).isEqualTo(persistedBefore);
        assertThat(counter(LorastatsBatchWriter.WRITE_FAILED_METRIC)).isEqualTo(failuresBefore + 1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM lorastats WHERE uid = 'metrics-check-failure'",
                                Integer.class))
                .isZero();
    }

    /**
     * The actual acceptance criterion: read the record back and prove nothing was
     * lost. Byte-for-byte string equality is not reconstructible from typed columns by
     * design (typed columns store values, not the original encoding), so the equivalent
     * assertion is structural equality of the rebuilt JSON against what was received —
     * exact on every value and type, and blind only to key order.
     *
     * <p>This is the test that would have caught record_version and passthrough going
     * unmapped: every member it covers is optional, so no schema test can see them
     * disappear.</p>
     */
    @Test
    void aStoredRecordRebuildsIntoTheRecordThatWasReceived() throws Exception {
        ObjectNode received = objectMapper.createObjectNode();
        received.put("uid", "round-trip-proof");
        received.put("tenant", "tenant-a");
        received.put("maintenant", "management-parent");
        received.put("clsbox", "A1B2C3D4E5F6");
        received.put("type", "lorastats");
        received.put("version", 3);
        ObjectNode ts = received.putObject("ts");
        ts.put("server", DEVICE_TIME.getEpochSecond());
        ts.put("device", DEVICE_TIME.getEpochSecond());
        ts.put("datastore", DEVICE_TIME.getEpochSecond() + 1);
        ts.put("converter", DEVICE_TIME.getEpochSecond() + 2);
        ts.put("expiresOn", "2097-07-13T12:28:59.074Z");
        ObjectNode data = completeData(30);
        data.put("rx_future_counter", 99L);
        received.set("data", data);
        received.putArray("status").add("conversion ok");

        StatsEnvelope record = objectMapper.treeToValue(received, StatsEnvelope.class);
        assertThat(writer.write(List.of(admitted(record))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT * FROM lorastats WHERE ts_device = ? AND uid = ?",
                        Timestamp.from(DEVICE_TIME),
                        "round-trip-proof");

        // Compared by VALUE, not by node identity. JSON has one number type and
        // Jackson has several, so a 99 written as a long comes back from jsonb as an
        // IntNode and LongNode(99).equals(IntNode(99)) is false -- a difference in how
        // the tree was built, not in what was stored. Jackson's comparator overload is
        // the intended way to say that. Key ORDER is likewise not preserved by jsonb
        // and is not part of the contract; every value and type is.
        assertThat(rebuild(row).equals(VALUE_EQUALITY, received))
                .as("rebuilt %s%nreceived %s", rebuild(row), received)
                .isTrue();
    }

    /** Numeric nodes compare by value; everything else by normal node equality. */
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
        rebuilt.put("type", StatType.LORASTATS.wireName());
        rebuilt.put("version", ((Number) row.get("record_version")).intValue());

        ObjectNode ts = rebuilt.putObject("ts");
        ts.put("server", ((Timestamp) row.get("ts_server")).toInstant().getEpochSecond());
        ts.put("device", ((Timestamp) row.get("ts_device")).toInstant().getEpochSecond());
        JsonNode passthrough = readJson(row.get("passthrough"));
        passthrough.path("ts").properties().forEach(entry -> ts.set(entry.getKey(), entry.getValue()));
        rebuilt.set("status", passthrough.get("status"));

        ObjectNode data = rebuilt.putObject("data");
        for (String field : LorastatsBatchWriter.MAPPED_DATA_FIELDS) {
            Object value = row.get(field);
            if (value != null) {
                data.put(field, ((Number) value).longValue());
            }
        }
        readJson(row.get("data_extra")).properties()
                .forEach(entry -> data.set(entry.getKey(), entry.getValue()));
        return rebuilt;
    }

    /**
     * The schema's digit pattern caps at 19 digits, which is wider
     * than a signed BIGINT, and the gap is not expressible as a regex — so the Writer
     * has to range-check before converting. An unchecked conversion wraps and stores a
     * corrupted counter with no error anywhere.
     */
    @Test
    void acceptsTheBigintMaximumAndRejectsAnythingWiderThanIt() {
        ObjectNode atLimit = completeData(0);
        atLimit.put("rx_nr_pkts", Long.MAX_VALUE);

        assertThat(writer.write(List.of(admitted(envelope("bigint-max", "tenant-a", atLimit)))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT rx_nr_pkts FROM lorastats WHERE ts_device = ? AND uid = ?",
                                Long.class,
                                Timestamp.from(DEVICE_TIME),
                                "bigint-max"))
                .isEqualTo(Long.MAX_VALUE);

        ObjectNode overflowing = completeData(0);
        // Long.MAX_VALUE + 1: still inside the schema's 19-digit pattern.
        overflowing.put("rx_nr_pkts", new java.math.BigInteger("9223372036854775808"));

        List<RecordWriteResult> results =
                writer.write(List.of(admitted(envelope("bigint-overflow", "tenant-a", overflowing))));
        assertThat(results).extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.FAILED);
        Throwable failure = results.getFirst().failureOptional().orElseThrow();
        assertThat(failure).hasMessageContaining("rx_nr_pkts").hasMessageContaining("BIGINT");
        // The same verdict as the other writers' -- an overflow is
        // PERMANENT and reaches the DLQ on attempt one. Before WireNumbers this path threw
        // an IllegalArgumentException, which the classifier retries through all three
        // tiers before dead-lettering the identical failure 35 minutes later.
        assertThat(failure).isInstanceOf(PermanentFailureException.class);
        assertThat(classifier.isPermanent(failure)).isTrue();
    }

    /**
     * Both spellings of one device must land on ONE key in
     * {@code idx_lorastats_tenant_clsbox_device_uid}.
     *
     * <p>The failure this guards is not an error. Stored as received, {@code 04b6…}
     * and {@code 04B6…} are two distinct index keys, so the API's case-insensitive
     * {@code clsbox} filter — half of the dominant query pattern — answers 200
     * with a silently partial page, and the missing rows are indistinguishable from a
     * device that reported nothing. Asserting on the equality predicate rather than on
     * the column alone is deliberate: it is the predicate that would have been wrong.
     */
    @Test
    void storesOneCanonicalClsboxCaseSoTheDeviceCannotSplitAcrossTwoKeys() {
        writer.write(
                List.of(
                        admitted(envelope("clsbox-upper", "tenant-a", "04B64810652B", completeData(20))),
                        admitted(envelope("clsbox-lower", "tenant-a", "04b64810652b", completeData(21)))));

        assertThat(
                        jdbc.queryForList(
                                "SELECT clsbox FROM lorastats WHERE uid IN (?, ?)",
                                String.class,
                                "clsbox-upper",
                                "clsbox-lower"))
                .containsExactly("04B64810652B", "04B64810652B");

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM lorastats WHERE tenant = ? AND clsbox = ?",
                                Long.class,
                                "tenant-a",
                                "04B64810652B"))
                .as("both spellings answer one exact-match lookup on the covering index")
                .isEqualTo(2L);
    }

    /**
     * The V5 CHECK is the backstop behind the Writer's normalisation, not a duplicate
     * of it: the next stat type's writer cannot omit the uppercasing and have it pass
     * unnoticed. Written against the table directly because no application path can
     * reach it any more.
     */
    @Test
    void theTableRefusesANonCanonicalClsboxEvenWhenTheWriterIsBypassed() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO lorastats (uid, tenant, maintenant, clsbox,"
                                            + " ts_server, ts_device, schema_version, rx_nr_pkts,"
                                            + " rx_crc_missing, rx_crc_bad, tx_success, tx_failed,"
                                            + " tx_peek, tx_peek_index_zero, tx_override, tx_busy,"
                                            + " tx_dequeue_error, tx_type_beacon, chip_comm_error,"
                                            + " util_fopen_failed) VALUES (?, ?, ?, ?, ?, ?, 1, 0,"
                                            + " 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)",
                                        "clsbox-check",
                                        "tenant-a",
                                        "management-parent",
                                        "04b64810652b",
                                        Timestamp.from(DEVICE_TIME),
                                        Timestamp.from(DEVICE_TIME)))
                .hasMessageContaining("lorastats_clsbox_canonical_hex");
    }

    private JsonNode readJson(Object columnValue) {
        assertThat(columnValue).isNotNull();
        try {
            return objectMapper.readTree(columnValue.toString());
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new AssertionError("stored column is not valid JSON", failure);
        }
    }

    /**
     * The writer takes validated input by signature, so every test record
     * carries the schema label its catalogue entry would have supplied. Building it here
     * rather than mocking a gate keeps these tests about persistence: what the gate does
     * with a record it refuses is PayloadGateTest's subject, and what the two do together
     * is LorastatsPayloadGateIntegrationTest's.
     */
    private static ValidatedRecord admitted(StatsEnvelope envelope) {
        return ValidatedRecord.of(envelope, "v1");
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
                StatType.LORASTATS,
                1,
                new Timestamps(DEVICE_TIME, DEVICE_TIME, null, null, null, null),
                data,
                null,
                null);
    }

    // ---- Three dialects, one stored shape --------------------

    /**
     * Rule 2, proved where it actually has to hold: in the table. Three records carrying
     * the same counters in three mutually incompatible dialects must produce three
     * identical rows, or "one stored shape, one API shape" is a claim in a decision log
     * rather than a property of the database. The API reads columns; it has no way to
     * discover that {@code rx_nr_pkts} was spelled {@code lora_rx_nr_pkts} on the wire,
     * and it must not need one.
     */
    @Test
    void allThreeDialectsLandAsOneIdenticalRow() {
        writer.write(
                List.of(
                        admitted(envelope("dialect-a", "tenant-a", bareInteger())),
                        admitted(envelope("dialect-b", "tenant-a", bareString())),
                        admitted(envelope("dialect-c", "tenant-a", prefixedInteger()))));

        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        """
                        SELECT rx_nr_pkts, rx_crc_missing, rx_crc_bad, rx_short_pkt,
                               tx_success, tx_failed, tx_peek, tx_peek_index_zero,
                               tx_override, tx_busy, tx_dequeue_error, tx_type_beacon,
                               chip_comm_error, util_fopen_failed, fsk_received,
                               rx_type_arol, timespan, data_extra
                        FROM lorastats WHERE uid IN ('dialect-a', 'dialect-b', 'dialect-c')
                        ORDER BY uid
                        """);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(1)).as("dialect B matches dialect A").isEqualTo(rows.get(0));
        assertThat(rows.get(2)).as("dialect C matches dialect A").isEqualTo(rows.get(0));
        assertThat(rows.get(0)).containsEntry("rx_nr_pkts", 11L).containsEntry("timespan", 27L);
    }

    /**
     * The regression that would otherwise be invisible. If the {@code data_extra} residue
     * were taken from the wire shape instead of the normalised one, every dialect-C
     * record would drop fifteen {@code lora_}-prefixed members into that column, leave
     * the typed columns NULL, and increment {@code records_unmapped_data_field} — a
     * counter whose entire job is to say "Heimdal added a field" — on ordinary traffic
     * every ten minutes.
     */
    @Test
    void aPrefixedDialectRecordFillsColumnsAndLeavesDataExtraNull() {
        double unmappedBefore = counter(LorastatsBatchWriter.UNMAPPED_DATA_METRIC);

        assertThat(writer.write(List.of(admitted(envelope("prefixed-columns", "tenant-a", prefixedInteger())))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);

        Map<String, Object> stored =
                jdbc.queryForMap(
                        "SELECT rx_nr_pkts, timespan, data_extra FROM lorastats "
                                + "WHERE ts_device = ? AND uid = ?",
                        Timestamp.from(DEVICE_TIME),
                        "prefixed-columns");

        assertThat(stored).containsEntry("rx_nr_pkts", 11L).containsEntry("data_extra", null);
        assertThat(counter(LorastatsBatchWriter.UNMAPPED_DATA_METRIC)).isEqualTo(unmappedBefore);
    }

    /** Each dialect is counted under its own label, and nothing is stored to say so. */
    @Test
    void metersTheDialectEachRecordArrivedIn() {
        double before = dialectCount("c_prefixed_integer");

        writer.write(List.of(admitted(envelope("dialect-metered", "tenant-a", prefixedInteger()))));

        assertThat(dialectCount("c_prefixed_integer")).isEqualTo(before + 1.0d);
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = 'lorastats'", String.class))
                .as("the dialect is a metric label, never a column")
                .noneMatch(column -> column.contains("dialect"));
    }

    /**
     * Range-checked coercion, at the boundary the schema cannot express. The record is
     * SCHEMA-VALID — nineteen digits, matching the counterString pattern — and the Writer
     * refuses it before conversion. A coercion that caught the overflow and stored 0
     * would produce a row that is wrong rather than missing, with nothing reporting it.
     */
    @Test
    void refusesAStringCounterThatOverflowsBigintAndWritesNothing() {
        ObjectNode overflowing = bareString();
        overflowing.put("rx_nr_pkts", "9223372036854775808");

        List<RecordWriteResult> results =
                writer.write(List.of(admitted(envelope("string-overflow", "tenant-a", overflowing))));

        assertThat(results).extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.FAILED);
        assertThat(results.getFirst().failureOptional().orElseThrow())
                .isInstanceOf(PermanentFailureException.class)
                .hasMessageContaining("rx_nr_pkts");
        assertThat(classifier.isPermanent(results.getFirst().failureOptional().orElseThrow()))
                .as("an overflow skips the retry tiers; waiting cannot make it fit")
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM lorastats WHERE uid = ?",
                                Integer.class,
                                "string-overflow"))
                .isZero();
    }

    /** Rule 4 reaching the database layer: an unknown spelling writes nothing. */
    @Test
    void refusesAnUnknownDialectAndWritesNothing() {
        ObjectNode fourth = objectMapper.createObjectNode();
        LorastatsDialects.REQUIRED_FIELDS.forEach(field -> fourth.put("lorawan_" + field, 1L));

        List<RecordWriteResult> results =
                writer.write(List.of(admitted(envelope("fourth-dialect", "tenant-a", fourth))));

        assertThat(results).extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.FAILED);
        assertThat(classifier.isPermanent(results.getFirst().failureOptional().orElseThrow()))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM lorastats WHERE uid = ?",
                                Integer.class,
                                "fourth-dialect"))
                .isZero();
    }

    /**
     * The column names the schema that ADMITTED the row, so it comes
     * from the validator's result and not from a constant compiled into this class. A
     * constant answers a different question — which schema the author had in mind — and
     * the two agree only until a v2 exists, at which point every row already stored
     * claims the wrong one and nothing fails.
     */
    @Test
    void storesTheSchemaVersionTheGateReturnedRatherThanAConstant() {
        StatsEnvelope record = envelope("schema-label", "tenant-a", completeData(0));

        assertThat(writer.write(List.of(ValidatedRecord.of(record, "v7"))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.INSERTED);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT schema_version FROM lorastats WHERE ts_device = ? AND uid = ?",
                                Integer.class,
                                Timestamp.from(DEVICE_TIME),
                                "schema-label"))
                .isEqualTo(7);
    }

    /**
     * A payload nobody can map and a database that rejects a row are different events,
     * and the metrics have to say so. {@code records_write_failed} is a signal about
     * <em>PostgreSQL</em>: whoever watches it climb reaches for the database first. An
     * unknown dialect never reaches PostgreSQL at all — it fails while the row is being
     * bound, before a transaction is opened — so counting it there sends that person to
     * a database that is behaving perfectly while upstream quietly ships a shape nobody
     * recognises.
     */
    @Test
    void countsAnUnmappablePayloadApartFromAFailedWrite() {
        double writesBefore = counter(LorastatsBatchWriter.WRITE_FAILED_METRIC);
        double unmappableBefore = rejected("unmappable");

        ObjectNode fourthDialect = objectMapper.createObjectNode();
        LorastatsDialects.REQUIRED_FIELDS.forEach(field -> fourthDialect.put("lorawan_" + field, 1L));

        assertThat(writer.write(List.of(admitted(envelope("unmappable-counted", "tenant-a", fourthDialect)))))
                .extracting(RecordWriteResult::status)
                .containsExactly(RecordWriteStatus.FAILED);

        assertThat(rejected("unmappable"))
                .as("an unmappable payload is counted as a refusal")
                .isEqualTo(unmappableBefore + 1.0d);
        assertThat(counter(LorastatsBatchWriter.WRITE_FAILED_METRIC))
                .as("...and NOT as a write failure — no write was ever attempted")
                .isEqualTo(writesBefore);
    }

    private double rejected(String reason) {
        io.micrometer.core.instrument.Counter counter =
                meterRegistry
                        .find(com.zenner.datahub.stats.writer.validation.WriterGateMetrics
                                .PAYLOAD_REJECTED)
                        .tag("type", StatType.LORASTATS.wireName())
                        .tag("reason", reason)
                        .counter();
        return counter == null ? 0.0d : counter.count();
    }

    private ObjectNode bareInteger() {
        return completeData(10);
    }

    private ObjectNode bareString() {
        ObjectNode data = objectMapper.createObjectNode();
        completeData(10).properties()
                .forEach(member -> data.put(member.getKey(), member.getValue().asText()));
        return data;
    }

    private ObjectNode prefixedInteger() {
        ObjectNode data = objectMapper.createObjectNode();
        // Faithful to the production record: the counters are prefixed, fsk_received and
        // timespan are NOT. A helper that prefixed everything would test a dialect that
        // does not exist.
        completeData(10).properties()
                .forEach(
                        member -> {
                            String name = member.getKey();
                            boolean bare = name.equals("fsk_received") || name.equals("timespan");
                            data.set(bare ? name : "lora_" + name, member.getValue());
                        });
        return data;
    }

    private double dialectCount(String label) {
        io.micrometer.core.instrument.Counter counter =
                meterRegistry.find(LorastatsBatchWriter.DIALECT_METRIC).tag("dialect", label).counter();
        return counter == null ? 0.0d : counter.count();
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
