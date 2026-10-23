package com.zenner.datahub.stats.writer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.testsupport.PostgresSupport;
import com.zenner.datahub.stats.testsupport.RabbitSupport;
import com.zenner.datahub.stats.writer.StatsWriterApplication;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * An {@code ltestats} or {@code netstats} payload reaches no log line at
 * ANY level — not ours, and not the frameworks' the Writer runs on.
 *
 * <p>The checklist says it; this is what makes it true. Every Writer outcome is driven
 * through the real broker — stored, duplicate-suppressed, unmappable on the BIGINT range,
 * rejected by the payload schema (dead-lettered) and expired on arrival — with this
 * project's loggers, Spring AMQP and Spring JDBC all at {@code TRACE}, the levels an
 * operator reaches for in an incident. The records carry distinctive values in every
 * sensitive member (subscriber, location, address, an unmapped extension) and the
 * captured console is searched for each. Two framework loggers print payloads at those
 * levels and are pinned in {@code application.properties}; raising their parent package,
 * as this test does, must not release them.</p>
 */
@SpringBootTest(classes = StatsWriterApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "stats.writer.stat-type=all",
            "stats.retention.schedule=-",
            "stats.retention.pre-create-on-startup=false",
            "logging.level.com.zenner=TRACE",
            "logging.level.org.springframework.amqp=TRACE",
            "logging.level.org.springframework.jdbc=TRACE",
            "logging.level.org.springframework.transaction=TRACE"
        })
@Import({PostgresSupport.class, LteNetstatsPayloadsAreNeverLoggedIntegrationTest.OwnBroker.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class LteNetstatsPayloadsAreNeverLoggedIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class OwnBroker {
        @Bean
        @ServiceConnection
        RabbitMQContainer payloadLoggingRabbit() {
            // Its own broker, so no other cached context's listener consumes these.
            return new RabbitMQContainer(DockerImageName.parse(RabbitSupport.IMAGE));
        }
    }

    private static final long EPOCH = Instant.parse("2095-12-17T12:00:00Z").getEpochSecond();
    private static final long EXPIRED = Instant.parse("2000-01-01T00:00:00Z").getEpochSecond();

    // Distinctive enough that a match can only be the payload itself.
    private static final String IMSI = "262017364019283";
    private static final String LAC = "5EC7AB0D";
    private static final String CID = "5EC7ED1";
    private static final String OPERATOR = "SentinelCarrier41";
    private static final String IP = "198.51.100.231";
    private static final String DNS = "192.0.2.199";
    private static final String EXTENSION = "payload-marker-5f1c";
    private static final List<String> SENTINELS = List.of(IMSI, LAC, CID, OPERATOR, IP, DNS, EXTENSION);

    private final ObjectMapper json = new ObjectMapper();
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private AmqpAdmin admin;
    @Autowired private MeterRegistry meters;

    @BeforeAll
    void partitions() {
        for (String type : List.of("ltestats", "netstats")) {
            jdbc.execute("CREATE TABLE " + type + "_log_guard_day PARTITION OF " + type
                    + " FOR VALUES FROM ('2095-12-17 00:00:00+00') TO ('2095-12-18 00:00:00+00')");
        }
    }

    @AfterAll
    void cleanup() {
        for (String type : List.of("ltestats", "netstats")) {
            admin.purgeQueue("app_statsstore." + type + ".dlq", false);
            jdbc.execute("DROP TABLE " + type + "_log_guard_day");
        }
    }

    @Test
    void noWriterOutcomeLogsAPayloadValue(CapturedOutput output) throws Exception {
        double expiredBefore = expiredOnArrival("ltestats") + expiredOnArrival("netstats");

        ObjectNode lte = lte("valid/ltestats-production.json", EPOCH);
        ObjectNode net = net("valid/netstats-production-healthy.json", EPOCH);
        publish(lte);
        publish(lte); // duplicate-suppressed
        publish(net);
        publish(lte("valid/ltestats-signal-overflow.json", EPOCH)); // unmappable, permanent
        publish(net("valid/netstats-extra-interface-overflow.json", EPOCH)); // unmappable, permanent
        publish(lte("invalid/ltestats-shape-complete-mixed.json", EPOCH)); // payload schema, DLQ
        publish(lte("valid/ltestats-production.json", EXPIRED)); // expired on arrival, ACKed
        publish(net("valid/netstats-production-healthy.json", EXPIRED));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(rows("ltestats", lte)).isEqualTo(1);
            assertThat(rows("netstats", net)).isEqualTo(1);
            assertThat(dlq("ltestats")).isEqualTo(2);
            assertThat(dlq("netstats")).isEqualTo(1);
            assertThat(expiredOnArrival("ltestats") + expiredOnArrival("netstats")).isEqualTo(expiredBefore + 2);
        });

        String logs = output.getAll();
        // Proof the capture saw the levels this test asks for, not an empty console.
        assertThat(logs).contains("\"event\":\"persisted\"", "\"event\":\"payload_rejected\"",
                "\"event\":\"expired_on_arrival\"");
        for (String sentinel : SENTINELS) {
            assertThat(logs).as("a log line carries the payload value %s", sentinel).doesNotContain(sentinel);
        }
    }

    private ObjectNode lte(String fixture, long ts) throws Exception {
        ObjectNode record = record(fixture, ts);
        ObjectNode data = (ObjectNode) record.get("data");
        if (data.has("imsi")) {
            data.put("imsi", IMSI).put("plmn", "26201").put("lac", LAC).put("cid", CID).put("operator", OPERATOR);
        }
        data.put("future_member", EXTENSION);
        return record;
    }

    private ObjectNode net(String fixture, long ts) throws Exception {
        ObjectNode record = record(fixture, ts);
        ObjectNode data = (ObjectNode) record.get("data");
        ObjectNode eth0 = (ObjectNode) data.get("eth0");
        eth0.put("ip", IP);
        eth0.putArray("dns").add(DNS);
        data.put("a_member_longer_than_an_interface_name", EXTENSION);
        return record;
    }

    private ObjectNode record(String fixture, long ts) throws Exception {
        try (var input = getClass().getResourceAsStream("/" + fixture)) {
            ObjectNode record = (ObjectNode) json.readTree(input);
            List.copyOf(record.properties()).stream()
                    .map(java.util.Map.Entry::getKey)
                    .filter(name -> name.startsWith("$"))
                    .forEach(record::remove);
            record.put("uid", "LOG-GUARD-" + UUID.randomUUID());
            ((ObjectNode) record.get("ts")).put("server", ts).put("device", ts);
            return record;
        }
    }

    private void publish(ObjectNode record) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(AmqpTopology.INPUT_EXCHANGE, record.path("type").asText(),
                new Message(json.writeValueAsBytes(record), properties));
    }

    private int rows(String type, ObjectNode record) {
        return jdbc.queryForObject("SELECT count(*) FROM " + type + " WHERE uid = ?", Integer.class,
                record.path("uid").asText());
    }

    private Object dlq(String type) {
        return admin.getQueueProperties("app_statsstore." + type + ".dlq").get("QUEUE_MESSAGE_COUNT");
    }

    private double expiredOnArrival(String type) {
        var counter = meters.find("records_expired_on_arrival").tag("type", type).counter();
        return counter == null ? 0 : counter.count();
    }
}
