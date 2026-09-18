package com.zenner.datahub.stats.writer.consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.testsupport.PostgresSupport;
import com.zenner.datahub.stats.testsupport.RabbitSupport;
import com.zenner.datahub.stats.writer.StatsWriterApplication;
import com.zenner.datahub.stats.writer.persistence.LorastatsBatchWriter;
import com.zenner.datahub.stats.writer.retry.FailedDeliveryRouter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Exercises the two at-least-once delivery windows where the durable outcome exists
 * but acknowledging the original broker delivery does not complete.
 *
 * <p>An acknowledgement failure after a database commit leaves the original available
 * for redelivery. An acknowledgement failure after a confirmed retry hand-off leaves
 * both the original and its retry copy available. Both cases deliberately prefer a
 * duplicate over a lost record, and PostgreSQL must reduce either path to one row while
 * the duplicate-suppression metric records what happened.</p>
 */
@SpringBootTest(
        classes = StatsWriterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "stats.writer.stat-type=lorastats",
            "stats.consumer.lorastats.concurrency=1",
            "stats.consumer.lorastats.prefetch=1",
            "stats.retention.schedule=-",
            "stats.retention.pre-create-on-startup=false"
        })
@Import({PostgresSupport.class, RabbitSupport.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrashWindowIntegrationTest {

    private static final StatType TYPE = StatType.LORASTATS;
    private static final String MAIN_QUEUE = AmqpTopology.mainQueue(TYPE);
    private static final String RETRY_QUEUE = AmqpTopology.retryQueue(TYPE, 1);
    private static final String DLQ = AmqpTopology.deadLetterQueue(TYPE);
    private static final String TEST_PARTITION = "lorastats_crash_window_day";
    private static final Instant DEVICE_TIME = Instant.parse("2096-12-08T00:00:00Z");

    private static final byte[] COMMIT_WINDOW_RECORD = record("commit-ack-crash-proof");
    private static final byte[] HANDOFF_WINDOW_RECORD = record("handoff-ack-crash-proof");

    /** Keeps the application listener stopped while this test drives the boundary directly. */
    @MockitoBean private StatsMessageProcessorAvailability applicationAvailability;

    @Autowired private LorastatsMessageProcessor realProcessor;
    @Autowired private FailedDeliveryRouter failedDeliveryRouter;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private com.zenner.datahub.stats.writer.metrics.QueueAgeMetrics queueAge;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private AmqpAdmin rabbitAdmin;
    @Autowired private JdbcTemplate jdbc;

    @BeforeAll
    void createTestPartition() {
        jdbc.execute(
                """
                CREATE TABLE %s PARTITION OF lorastats
                FOR VALUES FROM ('2096-12-08 00:00:00+00') TO ('2096-12-09 00:00:00+00')
                """.formatted(TEST_PARTITION));
    }

    @AfterEach
    void removeBrokerResidue() {
        rabbitAdmin.purgeQueue(MAIN_QUEUE, false);
        rabbitAdmin.purgeQueue(RETRY_QUEUE, false);
        rabbitAdmin.purgeQueue(DLQ, false);
    }

    @AfterAll
    void dropTestPartition() {
        jdbc.execute("DROP TABLE IF EXISTS " + TEST_PARTITION);
    }

    @Test
    void redeliveryAfterCommitBeforeAckIsSuppressedAndMetered() throws Exception {
        double duplicatesBefore = duplicateCount();
        LorastatsConsumer consumer = consumerUsing(realProcessor);
        Message original = delivery(COMMIT_WINDOW_RECORD, 41L);
        Channel channelLostAfterCommit = ackFailureChannel(41L);

        assertThatThrownBy(() -> consumer.consume(original, channelLostAfterCommit))
                .isInstanceOf(IOException.class)
                .hasMessage("simulated process crash before ACK completed");

        assertThat(storedCount("commit-ack-crash-proof")).isEqualTo(1);
        assertThat(duplicateCount()).isEqualTo(duplicatesBefore);

        Channel redeliveryChannel = mock(Channel.class);
        consumer.consume(delivery(COMMIT_WINDOW_RECORD, 42L), redeliveryChannel);

        assertThat(storedCount("commit-ack-crash-proof")).isEqualTo(1);
        assertThat(duplicateCount()).isEqualTo(duplicatesBefore + 1.0d);
        verify(redeliveryChannel).basicAck(42L, false);
    }

    @Test
    void redeliveryAfterConfirmedRetryBeforeAckProducesNoLossOrDuplicateRow() throws Exception {
        double duplicatesBefore = duplicateCount();
        AtomicBoolean databaseUnavailable = new AtomicBoolean(true);
        StatsMessageProcessor failsOnceThenPersists = new StatsMessageProcessor() {
            @Override
            public StatType statType() {
                return TYPE;
            }

            @Override
            public void process(StatType expectedType, Message message) throws Exception {
                if (databaseUnavailable.getAndSet(false)) {
                    throw new IllegalStateException("simulated transient database outage");
                }
                realProcessor.process(expectedType, message);
            }
        };
        LorastatsConsumer consumer = consumerUsing(failsOnceThenPersists);
        Message original = delivery(HANDOFF_WINDOW_RECORD, 51L);
        Channel channelLostAfterHandoff = ackFailureChannel(51L);

        assertThatThrownBy(() -> consumer.consume(original, channelLostAfterHandoff))
                .isInstanceOf(IOException.class)
                .hasMessage("simulated process crash before ACK completed");

        Message confirmedRetryCopy = rabbitTemplate.receive(RETRY_QUEUE, 2_000);
        assertThat(confirmedRetryCopy).isNotNull();
        assertThat(confirmedRetryCopy.getBody()).isEqualTo(HANDOFF_WINDOW_RECORD);
        assertThat(confirmedRetryCopy.getMessageProperties().getHeaders())
                .containsEntry(FailedDeliveryRouter.ATTEMPT_HEADER, 1);

        Channel originalRedeliveryChannel = mock(Channel.class);
        consumer.consume(delivery(HANDOFF_WINDOW_RECORD, 52L), originalRedeliveryChannel);

        Channel retryDeliveryChannel = mock(Channel.class);
        confirmedRetryCopy.getMessageProperties().setDeliveryTag(53L);
        consumer.consume(confirmedRetryCopy, retryDeliveryChannel);

        assertThat(storedCount("handoff-ack-crash-proof")).isEqualTo(1);
        assertThat(duplicateCount()).isEqualTo(duplicatesBefore + 1.0d);
        verify(originalRedeliveryChannel).basicAck(52L, false);
        verify(retryDeliveryChannel).basicAck(53L, false);
        assertThat(rabbitTemplate.receive(RETRY_QUEUE, 200)).isNull();
    }

    private LorastatsConsumer consumerUsing(StatsMessageProcessor processor) {
        when(applicationAvailability.requiredProcessor(TYPE)).thenReturn(processor);
        return new LorastatsConsumer(
                applicationAvailability, failedDeliveryRouter, meterRegistry, queueAge);
    }

    private static Channel ackFailureChannel(long deliveryTag) throws Exception {
        Channel channel = mock(Channel.class);
        doThrow(new IOException("simulated process crash before ACK completed"))
                .when(channel)
                .basicAck(deliveryTag, false);
        return channel;
    }

    private static Message delivery(byte[] body, long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryTag(deliveryTag);
        return new Message(body, properties);
    }

    private int storedCount(String uid) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM lorastats WHERE ts_device = ? AND uid = ?",
                Integer.class,
                Timestamp.from(DEVICE_TIME),
                uid);
    }

    private double duplicateCount() {
        return meterRegistry
                .get(LorastatsBatchWriter.DUPLICATE_METRIC)
                .tag("type", TYPE.wireName())
                .counter()
                .count();
    }

    private static byte[] record(String uid) {
        return """
                {
                  "uid":"%s",
                  "tenant":"tenant-a",
                  "maintenant":"management-parent",
                  "clsbox":"A1B2C3D4E5F6",
                  "type":"lorastats",
                  "version":1,
                  "ts":{"server":4005763200,"device":4005763200},
                  "data":{
                    "rx_nr_pkts":1487,"rx_crc_missing":12,"rx_crc_bad":31,
                    "tx_success":903,"tx_failed":4,"tx_peek":17,
                    "tx_peek_index_zero":2,"tx_override":0,"tx_busy":6,
                    "tx_dequeue_error":0,"tx_type_beacon":288,
                    "chip_comm_error":0,"util_fopen_failed":0
                  }
                }
                """.formatted(uid).getBytes(UTF_8);
    }
}
