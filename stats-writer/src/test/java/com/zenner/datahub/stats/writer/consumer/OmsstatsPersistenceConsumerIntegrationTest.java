package com.zenner.datahub.stats.writer.consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.testsupport.PostgresSupport;
import com.zenner.datahub.stats.testsupport.RabbitSupport;
import com.zenner.datahub.stats.writer.StatsWriterApplication;
import com.zenner.datahub.stats.writer.retry.FailedDeliveryRouter;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End to end for the third type: an omsstats record published to the ingress
 * exchange is consumed by the omsstats listener, committed, and ACKed; a redelivery is a
 * harmless no-op; a payload the schema refuses lands in <em>this type's</em> DLQ; and none
 * of it touches the lorastats path — with all three real consumers live in one context,
 * which is the development {@code all} shape.
 *
 * <p>Assertions are on shared state — rows, queue depths, DLQ depths — because other
 * cached contexts in this JVM may hold live listeners on the same broker; a
 * context-local counter could be incremented by a sibling context and read as zero here.</p>
 */
@SpringBootTest(
        classes = StatsWriterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "stats.writer.stat-type=all",
            "stats.consumer.omsstats.concurrency=1",
            "stats.consumer.omsstats.prefetch=1",
            "stats.consumer.sysstats.concurrency=1",
            "stats.consumer.sysstats.prefetch=1",
            "stats.consumer.lorastats.concurrency=1",
            "stats.consumer.lorastats.prefetch=1",
            "stats.retention.schedule=-",
            "stats.retention.pre-create-on-startup=false"
        })
@Import({PostgresSupport.class, RabbitSupport.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OmsstatsPersistenceConsumerIntegrationTest {

    private static final String OMSSTATS_QUEUE = AmqpTopology.mainQueue(StatType.OMSSTATS);
    private static final String OMSSTATS_DLQ = AmqpTopology.deadLetterQueue(StatType.OMSSTATS);
    private static final String LORASTATS_QUEUE = AmqpTopology.mainQueue(StatType.LORASTATS);
    private static final String LORASTATS_DLQ = AmqpTopology.deadLetterQueue(StatType.LORASTATS);
    private static final String OMSSTATS_PARTITION = "omsstats_t035_consumer_day";
    private static final String LORASTATS_PARTITION = "lorastats_t035_consumer_day";
    // 2096-11-07T12:00:00Z — a day no other test partitions.
    private static final Instant DEVICE_TIME = Instant.parse("2096-11-07T12:00:00Z");
    private static final long DEVICE_EPOCH = DEVICE_TIME.getEpochSecond();

    private static final byte[] OMSSTATS_RECORD = omsstats("omsstats-consumer-proof", "663");
    private static final byte[] OMSSTATS_POISON = omsstats("omsstats-poison-proof", "\"six hundred and sixty-three\"");
    private static final byte[] LORASTATS_RECORD =
            """
            {
              "uid":"lorastats-beside-omsstats-proof",
              "tenant":"tenant-a",
              "maintenant":"management-parent",
              "clsbox":"A1B2C3D4E5F6",
              "type":"lorastats",
              "version":1,
              "ts":{"server":%d,"device":%d},
              "data":{
                "rx_nr_pkts":1,"rx_crc_missing":0,"rx_crc_bad":0,
                "tx_success":1,"tx_failed":0,"tx_peek":0,
                "tx_peek_index_zero":0,"tx_override":0,"tx_busy":0,
                "tx_dequeue_error":0,"tx_type_beacon":0,
                "chip_comm_error":0,"util_fopen_failed":0
              }
            }
            """.formatted(DEVICE_EPOCH, DEVICE_EPOCH).getBytes(UTF_8);

    private static byte[] omsstats(String uid, String rxNrPkts) {
        return """
                {
                  "uid":"%s",
                  "tenant":"tenant-a",
                  "maintenant":"management-parent",
                  "clsbox":"a1b2c3d4e5f6",
                  "type":"omsstats",
                  "version":1,
                  "ts":{"server":%d,"device":%d,"converter":%d,"datastore":%d,"expiresOn":"2096-11-08T12:00:00.000Z"},
                  "data":{
                    "rx_nr_pkts":%s,"rx_valid_nr_pkts":105,"rx_crc_bad":528,
                    "device_count":16,"timespan":1800
                  },
                  "status":["conversion ok"]
                }
                """.formatted(uid, DEVICE_EPOCH, DEVICE_EPOCH, DEVICE_EPOCH, DEVICE_EPOCH, rxNrPkts).getBytes(UTF_8);
    }

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private AmqpAdmin rabbitAdmin;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RabbitListenerEndpointRegistry listenerRegistry;

    @BeforeAll
    void prepareInfrastructure() {
        for (String queue : new String[] {OMSSTATS_QUEUE, OMSSTATS_DLQ, LORASTATS_QUEUE, LORASTATS_DLQ}) {
            rabbitAdmin.purgeQueue(queue, false);
        }
        jdbc.execute(
                """
                CREATE TABLE %s PARTITION OF omsstats
                FOR VALUES FROM ('2096-11-07 00:00:00+00') TO ('2096-11-08 00:00:00+00')
                """.formatted(OMSSTATS_PARTITION));
        jdbc.execute(
                """
                CREATE TABLE %s PARTITION OF lorastats
                FOR VALUES FROM ('2096-11-07 00:00:00+00') TO ('2096-11-08 00:00:00+00')
                """.formatted(LORASTATS_PARTITION));
    }

    @AfterAll
    void cleanInfrastructure() {
        listenerRegistry.getListenerContainer(OmsstatsConsumer.LISTENER_ID).stop();
        listenerRegistry.getListenerContainer(LorastatsConsumer.LISTENER_ID).stop();
        for (String queue : new String[] {OMSSTATS_QUEUE, OMSSTATS_DLQ, LORASTATS_QUEUE, LORASTATS_DLQ}) {
            rabbitAdmin.purgeQueue(queue, false);
        }
        jdbc.execute("DROP TABLE IF EXISTS " + OMSSTATS_PARTITION);
        jdbc.execute("DROP TABLE IF EXISTS " + LORASTATS_PARTITION);
    }

    @Test
    void commitsThenAcknowledgesAndAbsorbsARedelivery() {
        publish(StatType.OMSSTATS, OMSSTATS_RECORD);
        await().atMost(ofSeconds(10)).untilAsserted(() -> {
            assertThat(storedOmsstats("omsstats-consumer-proof")).isEqualTo(1);
            assertThat(depth(OMSSTATS_QUEUE)).isZero();
        });
        // What landed: the integer timespan, clsbox in ONE canonical case, and ts.converter
        // preserved in passthrough with the stored clocks untouched.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT timespan || ':' || clsbox || ':' || rx_nr_pkts || ':' "
                                        + "|| (passthrough->'ts'->>'converter') || ':' "
                                        + "|| extract(epoch from ts_server)::bigint FROM omsstats "
                                        + "WHERE ts_device = ? AND uid = ?",
                                String.class,
                                Timestamp.from(DEVICE_TIME),
                                "omsstats-consumer-proof"))
                .isEqualTo("1800:A1B2C3D4E5F6:663:" + DEVICE_EPOCH + ":" + DEVICE_EPOCH);

        publish(StatType.OMSSTATS, OMSSTATS_RECORD);
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(depth(OMSSTATS_QUEUE)).isZero());
        assertThat(storedOmsstats("omsstats-consumer-proof")).as("ON CONFLICT DO NOTHING").isEqualTo(1);
        assertThat(depth(OMSSTATS_DLQ)).isZero();
    }

    /**
     * Rule 6 and the head-of-line trap together, with the real consumers: an omsstats
     * payload the schema refuses dead-letters into the omsstats DLQ on its first attempt
     * while a lorastats record published beside it is committed through its own
     * queue, and the lorastats DLQ stays empty.
     */
    @Test
    void aPoisonOmsstatsRecordDeadLettersInItsOwnQueueAndLorastatsIsUntouched() {
        int omsstatsDlqBefore = depth(OMSSTATS_DLQ);
        int lorastatsDlqBefore = depth(LORASTATS_DLQ);

        publish(StatType.OMSSTATS, OMSSTATS_POISON);
        publish(StatType.LORASTATS, LORASTATS_RECORD);

        await().atMost(ofSeconds(10)).untilAsserted(() -> {
            assertThat(depth(OMSSTATS_DLQ)).isEqualTo(omsstatsDlqBefore + 1);
            assertThat(storedLorastats("lorastats-beside-omsstats-proof")).isEqualTo(1);
            assertThat(depth(OMSSTATS_QUEUE)).isZero();
            assertThat(depth(LORASTATS_QUEUE)).isZero();
        });

        assertThat(storedOmsstats("omsstats-poison-proof")).as("nothing was written").isZero();
        assertThat(depth(LORASTATS_DLQ)).isEqualTo(lorastatsDlqBefore);
        // A schema failure is permanent: first attempt, no retry tier, and the copy is
        // labelled so an operator can see why without reading the payload.
        Message deadLettered = rabbitTemplate.receive(OMSSTATS_DLQ, 2_000);
        assertThat(deadLettered).isNotNull();
        assertThat(deadLettered.getMessageProperties().getHeaders())
                .containsEntry(FailedDeliveryRouter.ATTEMPT_HEADER, 1)
                .containsEntry(FailedDeliveryRouter.PERMANENT_HEADER, true)
                .containsEntry(FailedDeliveryRouter.FAILURE_HEADER, "PermanentFailureException");
        assertThat(deadLettered.getBody()).as("the original bytes, untouched").isEqualTo(OMSSTATS_POISON);
    }

    private void publish(StatType type, byte[] body) {
        rabbitTemplate.convertAndSend(AmqpTopology.INPUT_EXCHANGE, AmqpTopology.routingKey(type), body);
    }

    private int storedOmsstats(String uid) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM omsstats WHERE ts_device = ? AND uid = ?",
                Integer.class,
                Timestamp.from(DEVICE_TIME),
                uid);
    }

    private int storedLorastats(String uid) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM lorastats WHERE ts_device = ? AND uid = ?",
                Integer.class,
                Timestamp.from(DEVICE_TIME),
                uid);
    }

    private int depth(String queue) {
        Properties properties = rabbitAdmin.getQueueProperties(queue);
        assertThat(properties).isNotNull();
        return (Integer) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
    }
}
