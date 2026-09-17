package com.zenner.datahub.stats.writer.consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.DockerClient;
import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.testsupport.PostgresSupport;
import com.zenner.datahub.stats.testsupport.RabbitSupport;
import com.zenner.datahub.stats.writer.StatsWriterApplication;
import com.zenner.datahub.stats.writer.metrics.OperationalMetrics;
import com.zenner.datahub.stats.writer.retry.FailedDeliveryRouter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * Rehearses database loss and recovery against the complete Writer delivery path.
 *
 * <p>A short outage first proves that queued work remains visible and drains after the
 * database returns without losing a record or creating a second row. A sustained outage
 * then proves that the same path consumes the three configured retry tiers and retires
 * the only remaining copy to the inert dead-letter queue.</p>
 *
 * <p>Dedicated containers allow this test to pause PostgreSQL and shorten externally
 * configured retry delays without disrupting other integration-test contexts or
 * redeclaring their shared queues with incompatible arguments.</p>
 */
@SpringBootTest(
        classes = StatsWriterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "stats.writer.stat-type=lorastats",
            "stats.consumer.lorastats.concurrency=1",
            "stats.consumer.lorastats.prefetch=1",
            "stats.amqp.retry.tier1-delay=PT1S",
            "stats.amqp.retry.tier2-delay=PT1S",
            "stats.amqp.retry.tier3-delay=PT1S",
            "stats.amqp.confirm-timeout=PT2S",
            "stats.metrics.initial-delay=PT24H",
            "stats.retention.schedule=-",
            "spring.datasource.hikari.connection-timeout=1000",
            "spring.datasource.hikari.validation-timeout=1000",
            "spring.datasource.hikari.data-source-properties.socketTimeout=1"
        })
@Import(DatabaseOutageRecoveryIntegrationTest.Infrastructure.class)
class DatabaseOutageRecoveryIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Infrastructure {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>(PostgresSupport.IMAGE);
        }

        @Bean
        @ServiceConnection
        RabbitMQContainer rabbit() {
            return new RabbitMQContainer(RabbitSupport.IMAGE);
        }
    }

    private static final StatType TYPE = StatType.LORASTATS;
    private static final String MAIN_QUEUE = AmqpTopology.mainQueue(TYPE);
    private static final String DLQ = AmqpTopology.deadLetterQueue(TYPE);
    private static final List<String> RETRY_QUEUES = List.of(
            AmqpTopology.retryQueue(TYPE, 1),
            AmqpTopology.retryQueue(TYPE, 2),
            AmqpTopology.retryQueue(TYPE, 3));
    private static final List<String> RECOVERY_UIDS =
            List.of("outage-recovery-1", "outage-recovery-2", "outage-recovery-3");
    private static final String EXHAUSTED_UID = "outage-exhausted-proof";

    @Autowired private PostgreSQLContainer<?> postgres;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private AmqpAdmin rabbitAdmin;
    @Autowired private RabbitListenerEndpointRegistry listeners;
    @Autowired private OperationalMetrics operationalMetrics;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private JdbcTemplate jdbc;

    private boolean databasePaused;

    @AfterEach
    void restoreInfrastructure() {
        resumeDatabase();
        MessageListenerContainer listener = listener();
        if (!listener.isRunning()) {
            listener.start();
        }
        purgeAllQueues();
    }

    @Test
    void backlogSurvivesARecoverableOutageAndExhaustedWorkRemainsInTheDlq() {
        MessageListenerContainer listener = listener();
        listener.stop();
        pauseDatabase();

        long now = Instant.now().getEpochSecond();
        RECOVERY_UIDS.forEach(uid -> publish(record(uid, now)));

        await().atMost(ofSeconds(10)).untilAsserted(() -> {
            operationalMetrics.poll();
            assertThat(queueDepth(MAIN_QUEUE)).isEqualTo(RECOVERY_UIDS.size());
            assertThat(gauge(OperationalMetrics.QUEUE_DEPTH)).isEqualTo(RECOVERY_UIDS.size());
        });

        double retriedBefore = counter("records_retried");
        listener.start();
        await().atMost(ofSeconds(15)).untilAsserted(() -> {
            assertThat(counter("records_retried")).isGreaterThan(retriedBefore);
            assertThat(totalRetryDepth()).isPositive();
        });

        resumeDatabase();
        awaitForDatabase();

        await().atMost(ofSeconds(20)).untilAsserted(() -> {
            assertThat(storedCount(RECOVERY_UIDS)).isEqualTo(RECOVERY_UIDS.size());
            assertThat(totalWorkDepth()).isZero();
            assertThat(queueDepth(DLQ)).isZero();
        });
        assertThat(distinctStoredCount(RECOVERY_UIDS)).isEqualTo(RECOVERY_UIDS.size());

        pauseDatabase();
        byte[] exhaustedRecord = record(EXHAUSTED_UID, Instant.now().getEpochSecond());
        publish(exhaustedRecord);

        await().atMost(ofSeconds(30)).untilAsserted(() -> {
            assertThat(queueDepth(DLQ)).isOne();
            assertThat(totalWorkDepth()).isZero();
        });

        resumeDatabase();
        awaitForDatabase();
        await().during(ofSeconds(2)).atMost(ofSeconds(5)).untilAsserted(() -> {
            assertThat(queueDepth(DLQ)).isOne();
            assertThat(storedCount(List.of(EXHAUSTED_UID))).isZero();
        });

        Message deadLetter = rabbit.receive(DLQ, 2_000);
        assertThat(deadLetter).isNotNull();
        assertThat(deadLetter.getBody()).isEqualTo(exhaustedRecord);
        assertThat(deadLetter.getMessageProperties().getHeaders())
                .containsEntry(FailedDeliveryRouter.ATTEMPT_HEADER, 4)
                .containsEntry(FailedDeliveryRouter.PERMANENT_HEADER, false);
    }

    private void publish(byte[] body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbit.send(
                AmqpTopology.INPUT_EXCHANGE,
                AmqpTopology.routingKey(TYPE),
                new Message(body, properties));
    }

    private MessageListenerContainer listener() {
        MessageListenerContainer listener =
                listeners.getListenerContainer(LorastatsConsumer.LISTENER_ID);
        assertThat(listener).isNotNull();
        return listener;
    }

    private void pauseDatabase() {
        docker().pauseContainerCmd(postgres.getContainerId()).exec();
        databasePaused = true;
    }

    private void resumeDatabase() {
        if (databasePaused) {
            docker().unpauseContainerCmd(postgres.getContainerId()).exec();
            databasePaused = false;
        }
    }

    private void awaitForDatabase() {
        await().atMost(ofSeconds(15))
                .ignoreExceptions()
                .untilAsserted(() -> assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isOne());
    }

    private static DockerClient docker() {
        return DockerClientFactory.instance().client();
    }

    private int totalWorkDepth() {
        return queueDepth(MAIN_QUEUE) + totalRetryDepth();
    }

    private int totalRetryDepth() {
        return RETRY_QUEUES.stream().mapToInt(this::queueDepth).sum();
    }

    private int queueDepth(String queue) {
        Properties properties = rabbitAdmin.getQueueProperties(queue);
        assertThat(properties).isNotNull();
        return (int) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
    }

    private double gauge(String name) {
        return meterRegistry.get(name).tag("type", TYPE.wireName()).gauge().value();
    }

    private double counter(String name) {
        return meterRegistry.find(name).tag("type", TYPE.wireName()).counters().stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }

    private int storedCount(List<String> uids) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM lorastats WHERE uid IN (" + placeholders(uids) + ")",
                Integer.class,
                uids.toArray());
    }

    private int distinctStoredCount(List<String> uids) {
        return jdbc.queryForObject(
                "SELECT count(DISTINCT uid) FROM lorastats WHERE uid IN ("
                        + placeholders(uids)
                        + ")",
                Integer.class,
                uids.toArray());
    }

    private static String placeholders(List<String> values) {
        return String.join(",", java.util.Collections.nCopies(values.size(), "?"));
    }

    private void purgeAllQueues() {
        rabbitAdmin.purgeQueue(MAIN_QUEUE, false);
        RETRY_QUEUES.forEach(queue -> rabbitAdmin.purgeQueue(queue, false));
        rabbitAdmin.purgeQueue(DLQ, false);
    }

    private static byte[] record(String uid, long timestamp) {
        return """
                {
                  "uid":"%s",
                  "tenant":"tenant-a",
                  "maintenant":"management-parent",
                  "clsbox":"A1B2C3D4E5F6",
                  "type":"lorastats",
                  "version":1,
                  "ts":{"server":%d,"device":%d},
                  "data":{
                    "rx_nr_pkts":1487,"rx_crc_missing":12,"rx_crc_bad":31,
                    "tx_success":903,"tx_failed":4,"tx_peek":17,
                    "tx_peek_index_zero":2,"tx_override":0,"tx_busy":6,
                    "tx_dequeue_error":0,"tx_type_beacon":288,
                    "chip_comm_error":0,"util_fopen_failed":0
                  }
                }
                """.formatted(uid, timestamp, timestamp).getBytes(UTF_8);
    }
}
