package com.zenner.datahub.stats.writer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.testsupport.PostgresSupport;
import com.zenner.datahub.stats.testsupport.RabbitSupport;
import com.zenner.datahub.stats.writer.StatsWriterApplication;
import com.zenner.datahub.stats.writer.retry.FailedDeliveryRouter;
import com.zenner.datahub.stats.writer.retry.PermanentFailureException;
import com.zenner.datahub.stats.writer.validation.PayloadGate;
import com.zenner.datahub.stats.writer.retention.RetentionWindow;
import com.zenner.datahub.stats.writer.retention.PartitionPrecreator;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real migrations, numeric mapping, broker delivery, retention and cross-type isolation. */
@SpringBootTest(classes = StatsWriterApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"stats.writer.stat-type=all", "stats.retention.schedule=-",
                "stats.retention.pre-create-on-startup=false"})
@Import({PostgresSupport.class, LteNetstatsPersistenceIntegrationTest.NewTypeBroker.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LteNetstatsPersistenceIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class NewTypeBroker {
        @Bean
        @ServiceConnection
        RabbitMQContainer newTypeRabbit() {
            // Other cached contexts have active listeners. Use a separate broker so
            // assertions observe this context's acknowledgements and metrics only.
            return new RabbitMQContainer(DockerImageName.parse(RabbitSupport.IMAGE));
        }
    }

    private static final long EPOCH = Instant.parse("2095-12-15T12:00:00Z").getEpochSecond();
    private final ObjectMapper json = new ObjectMapper();
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LtestatsBatchWriter lte;
    @Autowired private NetstatsBatchWriter net;
    @Autowired private PayloadGate gate;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private AmqpAdmin admin;
    @Autowired private MeterRegistry meters;
    @Autowired private RetentionWindow retention;
    @Autowired private PartitionPrecreator precreator;

    @BeforeAll
    void partitions() {
        for (String type : List.of("ltestats", "netstats")) {
            jdbc.execute("CREATE TABLE " + type + "_new_slice_day PARTITION OF " + type
                    + " FOR VALUES FROM ('2095-12-15 00:00:00+00') TO ('2095-12-16 00:00:00+00')");
            admin.purgeQueue("app_statsstore." + type + ".dlq", false);
        }
    }

    @AfterAll
    void cleanup() {
        for (String type : List.of("ltestats", "netstats")) {
            admin.purgeQueue("app_statsstore." + type + ".dlq", false);
            jdbc.execute("DROP TABLE " + type + "_new_slice_day");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ltestats-production", "ltestats-production-2g-no-rsrp",
            "ltestats-production-unknown-technology", "ltestats-valid", "ltestats-boundary", "ltestats-live-2026-10-09"})
    void everyLteShapeMapsWithoutManufacturingValues(String fixture) throws Exception {
        ObjectNode record = fixture("valid/" + fixture + ".json");
        assertThat(write(record).status()).isEqualTo(RecordWriteStatus.INSERTED);
        var row = jdbc.queryForMap("SELECT rssi, rsrp, rsrq, timespan, access_technology, imsi, lac, cid, etxt, clsbox FROM ltestats WHERE uid=?",
                record.path("uid").asText());
        assertThat(row.get("clsbox")).isEqualTo(record.path("clsbox").asText().toUpperCase(java.util.Locale.ROOT));
        JsonNode data = record.path("data");
        if (data.has("etxt")) {
            assertThat(row.get("etxt")).isEqualTo(data.path("etxt").asText());
            for (String name : List.of("rssi", "rsrp", "rsrq", "imsi", "lac", "cid", "timespan", "access_technology")) {
                assertThat(row.get(name)).as(name).isNull();
            }
        } else {
            assertThat(row.get("imsi")).isEqualTo(data.path("imsi").asText());
            assertThat(row.get("lac")).isEqualTo(data.path("lac").asText());
            assertThat(row.get("cid")).isEqualTo(data.path("cid").asText());
            assertThat(row.get("access_technology")).isEqualTo(data.path("access_Technology").asText());
            if (!data.has("rsrp")) assertThat(row.get("rsrp")).isNull();
            if (!data.has("timespan")) assertThat(row.get("timespan")).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"netstats-production-healthy", "netstats-production-wwan-down", "netstats-extra-interface",
            "netstats-no-apipa", "netstats-supplied-sample", "netstats-boundary", "netstats-live-2026-10-09"})
    void networkInterfacesPreserveKnownAndUnknownMembers(String fixture) throws Exception {
        ObjectNode record = fixture("valid/" + fixture + ".json");
        assertThat(write(record).status()).isEqualTo(RecordWriteStatus.INSERTED);
        JsonNode stored = json.readTree(jdbc.queryForObject("SELECT interfaces::text FROM netstats WHERE uid=?",
                String.class, record.path("uid").asText()));
        for (var member : record.path("data").properties()) {
            if (!NetstatsBatchWriter.isInterfaceName(member.getKey())) continue;
            ObjectNode restored = stored.path(member.getKey()).deepCopy();
            for (String field : NetstatsBatchWriter.COUNTER_FIELDS) {
                if (restored.has(field)) restored.put(field, restored.path(field).asText());
            }
            assertThat(restored).isEqualTo(member.getValue());
        }
    }

    @Test
    void signedBoundsAndHexIdentifiersAreLossless() throws Exception {
        ObjectNode record = fixture("valid/ltestats-production.json");
        ObjectNode data = (ObjectNode) record.get("data");
        data.put("rssi", "-9223372036854775808");
        data.put("rsrp", "9223372036854775807");
        data.put("timespan", "9223372036854775807");
        data.put("imsi", "00012345678901234567");
        data.put("lac", "00aB");
        data.put("cid", "FFFFFFFFFFFFFFFF");
        assertThat(write(record).status()).isEqualTo(RecordWriteStatus.INSERTED);
        var row = jdbc.queryForMap("SELECT rssi, rsrp, timespan, imsi, lac, cid FROM ltestats WHERE uid=?", record.path("uid").asText());
        assertThat(row).containsEntry("rssi", Long.MIN_VALUE).containsEntry("rsrp", Long.MAX_VALUE)
                .containsEntry("timespan", Long.MAX_VALUE).containsEntry("imsi", "00012345678901234567")
                .containsEntry("lac", "00aB").containsEntry("cid", "FFFFFFFFFFFFFFFF");
    }

    @Test
    void dynamicMapUsesExactIntegersAndPreservesExtensions() throws Exception {
        ObjectNode record = fixture("valid/netstats-production-healthy.json");
        ObjectNode data = (ObjectNode) record.get("data");
        ObjectNode extra = data.putObject("ppp2");
        extra.put("tx", "9223372036854775807");
        extra.put("rx", "0");
        extra.putObject("future_counter").put("raw", "000007");
        data.putObject("future setting").put("enabled", true);
        assertThat(write(record).status()).isEqualTo(RecordWriteStatus.INSERTED);
        var row = jdbc.queryForMap("SELECT interfaces::text, data_extra::text FROM netstats WHERE uid=?", record.path("uid").asText());
        assertThat(json.readTree(row.get("interfaces").toString()).at("/ppp2/tx").asText()).isEqualTo("9223372036854775807");
        assertThat(json.readTree(row.get("interfaces").toString()).at("/ppp2/future_counter/raw").asText()).isEqualTo("000007");
        assertThat(json.readTree(row.get("data_extra").toString()).at("/future setting/enabled").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ltestats-production", "netstats-production-healthy"})
    void duplicatesAreSuppressedAndMeteredAndPassthroughIsUnchanged(String fixture) throws Exception {
        ObjectNode record = fixture("valid/" + fixture + ".json");
        ((ObjectNode) record.get("ts")).put("deviceOrig", EPOCH - 11).putObject("expiresOn").put("$date", "2095-12-16T12:00:00Z");
        record.putObject("meta").put("tsDeviceCorrected", true);
        double before = meters.get("records_duplicate_suppressed").tag("type", record.path("type").asText()).counter().count();
        assertThat(write(record).status()).isEqualTo(RecordWriteStatus.INSERTED);
        assertThat(write(record).status()).isEqualTo(RecordWriteStatus.DUPLICATE_SUPPRESSED);
        assertThat(meters.get("records_duplicate_suppressed").tag("type", record.path("type").asText()).counter().count()).isEqualTo(before + 1);
        JsonNode stored = json.readTree(jdbc.queryForObject("SELECT passthrough::text FROM " + record.path("type").asText() + " WHERE uid=?",
                String.class, record.path("uid").asText()));
        assertThat(stored.at("/ts/deviceOrig").asLong()).isEqualTo(EPOCH - 11);
        assertThat(stored.at("/ts/expiresOn")).isEqualTo(record.at("/ts/expiresOn"));
        assertThat(stored.path("meta")).isEqualTo(record.path("meta"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ltestats-signal-overflow", "netstats-counter-bigint-overflow", "netstats-extra-interface-overflow"})
    void schemaValidOverflowIsPermanentUnmappableAndAtomic(String fixture) throws Exception {
        ObjectNode record = fixture("valid/" + fixture + ".json");
        String type = record.path("type").asText();
        double before = meters.get("records_payload_rejected").tags("type", type, "reason", "unmappable").counter().count();
        RecordWriteResult result = write(record);
        assertThat(result.status()).isEqualTo(RecordWriteStatus.FAILED);
        assertThat(result.failureOptional().orElseThrow()).isInstanceOf(PermanentFailureException.class);
        assertThat(count(record)).isZero();
        assertThat(meters.get("records_payload_rejected").tags("type", type, "reason", "unmappable").counter().count()).isEqualTo(before + 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ltestats-production", "netstats-production-healthy"})
    void brokerDeliveryCommitsAndReplayKeepsOneRow(String fixture) throws Exception {
        ObjectNode record = fixture("valid/" + fixture + ".json");
        publish(record);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(count(record)).isEqualTo(1));
        publish(record);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(count(record)).isEqualTo(1);
            assertThat(admin.getQueueProperties("app_statsstore." + record.path("type").asText()).get("QUEUE_MESSAGE_COUNT")).isEqualTo(0);
        });
    }

    @Test
    void poisonIsolatedFromTheOtherNewTypeAndDeadLetterBytesAreUnchanged() throws Exception {
        for (boolean ltePoison : List.of(true, false)) {
            ObjectNode poison = fixture(ltePoison ? "invalid/ltestats-shape-complete-mixed.json" : "valid/netstats-extra-interface-overflow.json");
            ObjectNode valid = fixture(ltePoison ? "valid/netstats-production-wwan-down.json" : "valid/ltestats-production-2g-no-rsrp.json");
            admin.purgeQueue("app_statsstore." + poison.path("type").asText() + ".dlq", false);
            byte[] body = json.writeValueAsBytes(poison);
            publish(poison);
            publish(valid);
            String dlq = "app_statsstore." + poison.path("type").asText() + ".dlq";
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(count(valid)).isEqualTo(1);
                assertThat(admin.getQueueProperties(dlq).get("QUEUE_MESSAGE_COUNT")).isEqualTo(1);
            });
            Message failed = rabbit.receive(dlq, 2_000);
            assertThat(failed).isNotNull();
            assertThat(failed.getBody()).isEqualTo(body);
            assertThat(failed.getMessageProperties().getHeaders())
                    .containsEntry(FailedDeliveryRouter.PERMANENT_HEADER, true)
                    .containsEntry(FailedDeliveryRouter.ATTEMPT_HEADER, 1);
            assertThat(count(poison)).isZero();
        }
    }

    @Test
    void validExpiredMessagesAreSkippedWithoutRowsOrDeadLetters() throws Exception {
        for (String fixture : List.of("ltestats-production", "netstats-production-healthy")) {
            ObjectNode record = fixture("valid/" + fixture + ".json");
            ((ObjectNode) record.get("ts")).put("server", 946684800L).put("device", 946684800L);
            String type = record.path("type").asText();
            String queue = "app_statsstore." + type;
            admin.purgeQueue(queue + ".dlq", false);
            double before = meters.get("records_expired_on_arrival").tag("type", type).counter().count();
            publish(record);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(meters.get("records_expired_on_arrival").tag("type", type).counter().count()).isGreaterThan(before));
            assertThat(count(record)).isZero();
            assertThat(admin.getQueueProperties(queue + ".dlq").get("QUEUE_MESSAGE_COUNT")).isEqualTo(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ltestats", "netstats"})
    void exactRetentionCutoffIsStoredWhileOneSecondEarlierIsSkipped(String name) throws Exception {
        StatType type = StatType.fromWireName(name);
        var day = retention.cutoffDay(type);
        long cutoff = day.atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond();
        String partition = name + "_" + day.format(java.time.format.DateTimeFormatter.ofPattern("uuuu_MM_dd"));
        boolean created = jdbc.queryForObject("SELECT to_regclass(?)", String.class, partition) == null;
        precreator.ensurePartitions(type, day, 0);
        String fixture = "valid/" + name + "-production" + (type == StatType.NETSTATS ? "-healthy" : "") + ".json";
        ObjectNode retained = fixture(fixture);
        ObjectNode expired = fixture(fixture);
        ((ObjectNode) retained.get("ts")).put("device", cutoff);
        ((ObjectNode) expired.get("ts")).put("device", cutoff - 1);
        double before = meters.get("records_expired_on_arrival").tag("type", name).counter().count();
        try {
            publish(retained);
            publish(expired);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(count(retained)).isEqualTo(1);
                assertThat(count(expired)).isZero();
                assertThat(meters.get("records_expired_on_arrival").tag("type", name).counter().count()).isEqualTo(before + 1);
            });
        } finally {
            jdbc.update("DELETE FROM " + name + " WHERE uid=?", retained.path("uid").asText());
            if (created) jdbc.execute("DROP TABLE " + partition);
        }
    }

    @Test
    void migrationsCarryPartitionKeysIndexesAndLteShapeConstraint() {
        for (String type : List.of("ltestats", "netstats")) {
            assertThat(jdbc.queryForObject("SELECT pg_get_partkeydef('" + type + "'::regclass)", String.class)).isEqualTo("RANGE (ts_device)");
            assertThat(jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE tablename=?", String.class, type))
                    .hasSize(3).anySatisfy(index -> assertThat(index).contains("(tenant, clsbox, ts_device DESC, uid DESC)"))
                    .anySatisfy(index -> assertThat(index).contains("(tenant, uid)"));
        }
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ltestats (uid,tenant,maintenant,clsbox,ts_server,ts_device,schema_version,etxt,rssi)"
                + " VALUES ('bad-shape','x','y','A1B2C3D4E5F6',to_timestamp(?),to_timestamp(?),1,'unknown',-1)", EPOCH, EPOCH))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ObjectNode fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/" + name)) {
            ObjectNode record = (ObjectNode) json.readTree(input);
            record.remove(List.of("$expect", "$expectCode", "$comment"));
            record.put("uid", "NEW-" + UUID.randomUUID());
            ((ObjectNode) record.get("ts")).put("server", EPOCH).put("device", EPOCH);
            return record;
        }
    }

    private RecordWriteResult write(ObjectNode record) throws Exception {
        StatType type = StatType.fromWireName(record.path("type").asText());
        var validated = gate.admit(type, new Message(json.writeValueAsBytes(record)));
        return (type == StatType.LTESTATS ? lte : net).write(List.of(validated)).getFirst();
    }

    private void publish(ObjectNode record) throws Exception {
        rabbit.send(AmqpTopology.INPUT_EXCHANGE, record.path("type").asText(), new Message(json.writeValueAsBytes(record)));
    }

    private int count(ObjectNode record) {
        return jdbc.queryForObject("SELECT count(*) FROM " + record.path("type").asText() + " WHERE uid=?", Integer.class, record.path("uid").asText());
    }
}
