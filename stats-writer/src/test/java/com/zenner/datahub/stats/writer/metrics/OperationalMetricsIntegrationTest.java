package com.zenner.datahub.stats.writer.metrics;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.testsupport.PostgresSupport;
import com.zenner.datahub.stats.testsupport.RabbitSupport;
import com.zenner.datahub.stats.writer.StatsWriterApplication;
import com.zenner.datahub.stats.writer.persistence.LorastatsBatchWriter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * Operational metrics against a real broker and a real Prometheus scrape.
 *
 * <p>Dedicated containers rather than the shared {@code RabbitSupport} ones, on purpose:
 * this test stops the lorastats listener to build a visible backlog, and on the shared
 * broker another cached context's listener would quietly drain it.</p>
 */
@SpringBootTest(
        classes = StatsWriterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "stats.writer.stat-type=lorastats",
            "stats.retention.schedule=-",
            // The test drives poll() itself; a scheduled poll racing it would only add noise.
            "stats.metrics.initial-delay=PT24H"
        })
@AutoConfigureObservability(tracing = false)
@Import(OperationalMetricsIntegrationTest.Infrastructure.class)
class OperationalMetricsIntegrationTest {

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

    private static final String QUEUE = AmqpTopology.mainQueue(StatType.LORASTATS);
    private static final String DLQ = AmqpTopology.deadLetterQueue(StatType.LORASTATS);

    @Autowired private OperationalMetrics metrics;
    @Autowired private MeterRegistry registry;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private RabbitListenerEndpointRegistry listeners;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestRestTemplate rest;

    private double gauge(String name) {
        return registry.get(name).tag("type", "lorastats").gauge().value();
    }

    private double counter(String name) {
        return registry.get(name).tag("type", "lorastats").counter().count();
    }

    @Test
    void realQueueBacklogConsumerStopStartAndPersistenceAreScrapeable() {
        MessageListenerContainer listener = listeners.getListenerContainer("stats-writer-lorastats");
        assertThat(listener).isNotNull();

        listener.stop();
        try {
            rabbit.send("", QUEUE, new Message(new byte[] {'{'}));
            rabbit.send("", QUEUE, new Message(new byte[] {'{'}));
            rabbit.send("", DLQ, new Message(new byte[] {'{'}));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                metrics.poll();
                assertThat(gauge(OperationalMetrics.QUEUE_DEPTH)).isEqualTo(2);
                assertThat(gauge(OperationalMetrics.DLQ_DEPTH)).isEqualTo(1);
                assertThat(gauge(OperationalMetrics.CONSUMER_AVAILABILITY)).isZero();
            });
            var scrape = rest.getForEntity("/actuator/prometheus", String.class);
            assertThat(scrape.getStatusCode().value()).isEqualTo(200);
            assertThat(scrape.getBody())
                    .contains(
                            "queue_depth{type=\"lorastats\"} 2.0",
                            "dlq_depth{type=\"lorastats\"} 1.0",
                            "consumer_availability{type=\"lorastats\"} 0.0",
                            "retention_period_days{type=\"lorastats\"} 90.0",
                            "broker_metrics_collection_success{type=\"lorastats\"} 1.0");
        } finally {
            rabbit.receive(QUEUE, 1000);
            rabbit.receive(QUEUE, 1000);
            rabbit.receive(DLQ, 1000);
            listener.start();
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            metrics.poll();
            assertThat(gauge(OperationalMetrics.CONSUMER_AVAILABILITY)).isPositive();
            assertThat(gauge(OperationalMetrics.QUEUE_DEPTH)).isZero();
        });

        // One valid, current record through the real path: consumed, timed, persisted.
        double consumedBefore = counter("records_consumed");
        double persistedBefore = counter(LorastatsBatchWriter.PERSISTED_METRIC);
        long now = Instant.now().getEpochSecond();
        rabbit.send("", QUEUE, new Message(
                """
                {
                  "uid":"t027-persisted-proof",
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
                """.formatted(now, now).getBytes(UTF_8)));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(counter(LorastatsBatchWriter.PERSISTED_METRIC)).isEqualTo(persistedBefore + 1);
        });
        assertThat(counter("records_consumed")).isGreaterThanOrEqualTo(consumedBefore + 1);
        assertThat(registry.get("records_processing_duration").tag("type", "lorastats").timer().count())
                .isPositive();
        assertThat(counter(LorastatsBatchWriter.WRITE_FAILED_METRIC)).isZero();
        assertThat(counter(LorastatsBatchWriter.POSTGRES_ERROR_METRIC)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM lorastats WHERE uid = 't027-persisted-proof'",
                                Integer.class))
                .isOne();
        assertThat(rest.getForObject("/actuator/prometheus", String.class))
                .contains(
                        "records_persisted_total{type=\"lorastats\"} 1.0",
                        "records_consumed_total{type=\"lorastats\"}",
                        "records_processing_duration_seconds_count{type=\"lorastats\"}");
    }
}
