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
import com.zenner.datahub.stats.writer.persistence.LorastatsBatchWriter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** End-to-end proof that Rabbit ACK follows the committed T-022 outcome. */
@SpringBootTest(
        classes = StatsWriterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "stats.writer.stat-type=lorastats",
            "stats.consumer.lorastats.concurrency=1",
            "stats.consumer.lorastats.prefetch=1",
            "stats.retention.schedule=-"
        })
@Import({PostgresSupport.class, RabbitSupport.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LorastatsPersistenceConsumerIntegrationTest {

    private static final String QUEUE = AmqpTopology.mainQueue(StatType.LORASTATS);
    private static final String TEST_PARTITION = "lorastats_t022_consumer_day";
    private static final Instant DEVICE_TIME = Instant.parse("2096-11-05T12:00:00Z");

    private static final byte[] RECORD =
            """
            {
              "uid":"consumer-persistence-proof",
              "tenant":"tenant-a",
              "maintenant":"management-parent",
              "clsbox":"A1B2C3D4E5F6",
              "type":"lorastats",
              "version":1,
              "ts":{"server":4002955200,"device":4002955200},
              "data":{
                "rx_nr_pkts":1487,"rx_crc_missing":12,"rx_crc_bad":31,
                "tx_success":903,"tx_failed":4,"tx_peek":17,
                "tx_peek_index_zero":2,"tx_override":0,"tx_busy":6,
                "tx_dequeue_error":0,"tx_type_beacon":288,
                "chip_comm_error":0,"util_fopen_failed":0
              }
            }
            """.getBytes(UTF_8);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @BeforeAll
    void prepareInfrastructure() {
        rabbitAdmin.purgeQueue(QUEUE, false);
        jdbc.execute(
                """
                CREATE TABLE %s PARTITION OF lorastats
                FOR VALUES FROM ('2096-11-05 00:00:00+00') TO ('2096-11-06 00:00:00+00')
                """.formatted(TEST_PARTITION));
    }

    @AfterAll
    void cleanInfrastructure() {
        rabbitAdmin.purgeQueue(QUEUE, false);
        jdbc.execute("DROP TABLE IF EXISTS " + TEST_PARTITION);
        listenerRegistry.getListenerContainer(LorastatsConsumer.LISTENER_ID).stop();
    }

    @Test
    void commitsThenAcknowledgesAndAcknowledgesAMeteredRedelivery() {
        double duplicatesBefore = duplicateCount();

        publish();
        await().atMost(ofSeconds(10)).untilAsserted(() -> {
            assertThat(storedCount()).isEqualTo(1);
            assertThat(messageCount()).isZero();
        });

        publish();
        await().atMost(ofSeconds(10)).untilAsserted(() -> {
            assertThat(storedCount()).isEqualTo(1);
            assertThat(duplicateCount()).isEqualTo(duplicatesBefore + 1.0d);
            assertThat(messageCount()).isZero();
        });
    }

    private void publish() {
        rabbitTemplate.convertAndSend(
                AmqpTopology.INPUT_EXCHANGE,
                AmqpTopology.routingKey(StatType.LORASTATS),
                RECORD);
    }

    private int storedCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM lorastats WHERE ts_device = ? AND uid = ?",
                Integer.class,
                Timestamp.from(DEVICE_TIME),
                "consumer-persistence-proof");
    }

    private int messageCount() {
        Properties properties = rabbitAdmin.getQueueProperties(QUEUE);
        assertThat(properties).isNotNull();
        return (Integer) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
    }

    private double duplicateCount() {
        return meterRegistry
                .get(LorastatsBatchWriter.DUPLICATE_METRIC)
                .tag("type", StatType.LORASTATS.wireName())
                .counter()
                .count();
    }
}
