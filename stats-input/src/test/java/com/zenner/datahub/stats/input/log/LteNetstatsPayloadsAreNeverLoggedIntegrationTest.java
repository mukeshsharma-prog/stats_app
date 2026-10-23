package com.zenner.datahub.stats.input.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.input.StatsInputApplication;
import com.zenner.datahub.stats.testsupport.RabbitSupport;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * An {@code ltestats} or {@code netstats} payload (subscriber and network identifiers)
 * reaches no Input log line
 * at ANY level — not ours, and not the frameworks' Input runs on.
 *
 * <p>The Writer half is {@code stats-writer}'s test of the same name. Here every Input
 * outcome a record can have is driven through both doors against a real broker —
 * publisher-confirmed (200), envelope-rejected (400) and clock-skew-rejected (400) —
 * with this project's loggers, Spring Web and Spring AMQP all at {@code TRACE}, and the
 * captured console is searched for distinctive values planted in every sensitive
 * member.</p>
 */
@SpringBootTest(classes = StatsInputApplication.class)
@AutoConfigureMockMvc
@Import(RabbitSupport.class)
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
        properties = {
            "STATS_INPUT_AUTH_MODE=none",
            "STATS_INGEST_ROLE=stats.ingest",
            "stats.amqp.confirm-timeout=5s",
            "logging.level.com.zenner=TRACE",
            "logging.level.org.springframework.web=TRACE",
            "logging.level.org.springframework.amqp=TRACE",
            "logging.level.org.springframework.http=TRACE"
        })
class LteNetstatsPayloadsAreNeverLoggedIntegrationTest {

    private static final String IMSI = "262017364019283";
    private static final String LAC = "5EC7AB0D";
    private static final String CID = "5EC7ED1";
    private static final String OPERATOR = "SentinelCarrier41";
    private static final String IP = "198.51.100.231";
    private static final String DNS = "192.0.2.199";
    private static final String EXTENSION = "payload-marker-5f1c";
    private static final List<String> SENTINELS = List.of(IMSI, LAC, CID, OPERATOR, IP, DNS, EXTENSION);

    private static final List<StatType> TYPES = List.of(StatType.LTESTATS, StatType.NETSTATS);

    private final ObjectMapper json = new ObjectMapper();
    @Autowired private MockMvc mockMvc;
    @Autowired private AmqpAdmin amqpAdmin;
    @Autowired private TopicExchange statsInputExchange;

    @BeforeEach
    void bindBothTypes() {
        for (StatType type : TYPES) {
            Queue queue = new Queue(AmqpTopology.mainQueue(type), true, false, false,
                    AmqpTopology.mainQueueArguments());
            amqpAdmin.declareQueue(queue);
            amqpAdmin.declareBinding(BindingBuilder.bind(queue).to(statsInputExchange)
                    .with(AmqpTopology.routingKey(type)));
            amqpAdmin.purgeQueue(queue.getName(), false);
        }
    }

    @AfterEach
    void emptyBothQueues() {
        TYPES.forEach(type -> amqpAdmin.purgeQueue(AmqpTopology.mainQueue(type), false));
    }

    @Test
    void noInputOutcomeLogsAPayloadValue(CapturedOutput output) throws Exception {
        long now = Instant.now().getEpochSecond();
        for (String door : List.of("/internal/stats", "/external/stats")) {
            for (ObjectNode record : List.of(lte(now), net(now))) {
                mockMvc.perform(send(door, record)).andExpect(status().isOk());

                ObjectNode badEnvelope = record.deepCopy();
                badEnvelope.put("clsbox", "not-a-gateway");
                mockMvc.perform(send(door, badEnvelope)).andExpect(status().isBadRequest());

                ObjectNode skewed = record.deepCopy();
                ((ObjectNode) skewed.get("ts")).put("device", now + 3_600);
                mockMvc.perform(send(door, skewed)).andExpect(status().isBadRequest());
            }
        }

        String logs = output.getAll();
        // Proof the capture saw the levels this test asks for, not an empty console.
        assertThat(logs).contains("\"event\":\"published\"").contains("org.springframework.web");
        for (String sentinel : SENTINELS) {
            assertThat(logs).as("a log line carries the payload value %s", sentinel).doesNotContain(sentinel);
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder send(
            String door, ObjectNode record) throws Exception {
        ObjectNode copy = record.deepCopy();
        copy.put("uid", "LOG-GUARD-" + UUID.randomUUID());
        return post(door).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(copy));
    }

    private ObjectNode lte(long now) throws Exception {
        ObjectNode record = record("valid/ltestats-production.json", now);
        ((ObjectNode) record.get("data")).put("imsi", IMSI).put("plmn", "26201").put("lac", LAC).put("cid", CID)
                .put("operator", OPERATOR).put("future_member", EXTENSION);
        return record;
    }

    private ObjectNode net(long now) throws Exception {
        ObjectNode record = record("valid/netstats-production-healthy.json", now);
        ObjectNode data = (ObjectNode) record.get("data");
        ObjectNode eth0 = (ObjectNode) data.get("eth0");
        eth0.put("ip", IP);
        eth0.putArray("dns").add(DNS);
        data.put("a_member_longer_than_an_interface_name", EXTENSION);
        return record;
    }

    private ObjectNode record(String fixture, long now) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(fixture)) {
            ObjectNode record = (ObjectNode) json.readTree(Objects.requireNonNull(in, fixture));
            List.copyOf(record.properties()).stream()
                    .map(Map.Entry::getKey)
                    .filter(name -> name.startsWith("$"))
                    .forEach(record::remove);
            ((ObjectNode) record.get("ts")).put("server", now).put("device", now);
            return record;
        }
    }
}
