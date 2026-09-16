package com.zenner.datahub.stats.writer.consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.amqp.AmqpTopology.ExchangeDefinition;
import com.zenner.datahub.stats.common.amqp.AmqpTopology.QueueDefinition;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.testsupport.RabbitSupport;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;

/**
 * Proves that each statistic type has an independent delivery path through RabbitMQ.
 *
 * <p>The consumers deliberately use separate connections and channels. One consumer
 * holds a delivery without acknowledging it, which models a handler blocked on a
 * poison record. A healthy record for another type must still be delivered and
 * acknowledged through that type's own queue.</p>
 *
 * <p>A dedicated virtual host keeps the proof independent of application listeners
 * retained in Spring's test-context cache. Within that virtual host the test uses the
 * production topology definitions, so queue names, durability, quorum arguments, and
 * exact routing keys cannot drift from the application.</p>
 */
class PoisonMessageIsolationIntegrationTest {

    private static final StatType POISON_TYPE = StatType.LORASTATS;
    private static final StatType HEALTHY_TYPE = StatType.SYSSTATS;
    private static final byte[] POISON_BODY = "poison-lorastats-record".getBytes(UTF_8);
    private static final byte[] HEALTHY_BODY = "healthy-sysstats-record".getBytes(UTF_8);

    private final String virtualHost = "poison_isolation_" + UUID.randomUUID();
    private ConnectionFactory client;

    @BeforeEach
    void createIsolatedVirtualHost() throws Exception {
        client = new ConnectionFactory();
        client.setUri(RabbitSupport.container().getAmqpUrl());

        assertCommandSucceeded(exec("rabbitmqctl", "add_vhost", virtualHost));
        assertCommandSucceeded(
                exec(
                        "rabbitmqctl",
                        "set_permissions",
                        "-p",
                        virtualHost,
                        client.getUsername(),
                        ".*",
                        ".*",
                        ".*"));
        client.setVirtualHost(virtualHost);
    }

    @AfterEach
    void deleteIsolatedVirtualHost() throws Exception {
        if (client != null) {
            assertCommandSucceeded(exec("rabbitmqctl", "delete_vhost", virtualHost));
        }
    }

    @Test
    @DisplayName("a poison record for one type does not delay a healthy record for another")
    void poisonRecordCannotBlockAnotherType() throws Exception {
        CountDownLatch poisonReceived = new CountDownLatch(1);
        CountDownLatch releasePoison = new CountDownLatch(1);
        CountDownLatch healthyReceived = new CountDownLatch(1);
        AtomicReference<byte[]> poisonDelivery = new AtomicReference<>();
        AtomicReference<byte[]> healthyDelivery = new AtomicReference<>();

        try (Connection poisonConnection = client.newConnection("poison-type-consumer");
                Connection healthyConnection = client.newConnection("healthy-type-consumer");
                Channel poisonChannel = poisonConnection.createChannel();
                Channel healthyChannel = healthyConnection.createChannel()) {
            declarePerTypeTopology(poisonChannel, POISON_TYPE, HEALTHY_TYPE);
            poisonChannel.basicQos(1);
            healthyChannel.basicQos(1);

            poisonChannel.basicConsume(
                    AmqpTopology.mainQueue(POISON_TYPE),
                    false,
                    (consumerTag, delivery) -> {
                        poisonDelivery.set(delivery.getBody());
                        poisonReceived.countDown();
                        try {
                            if (releasePoison.await(15, SECONDS)) {
                                poisonChannel.basicReject(
                                        delivery.getEnvelope().getDeliveryTag(), false);
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    },
                    consumerTag -> {});
            healthyChannel.basicConsume(
                    AmqpTopology.mainQueue(HEALTHY_TYPE),
                    false,
                    (consumerTag, delivery) -> {
                        healthyDelivery.set(delivery.getBody());
                        healthyChannel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                        healthyReceived.countDown();
                    },
                    consumerTag -> {});

            poisonChannel.basicPublish(
                    AmqpTopology.INPUT_EXCHANGE,
                    AmqpTopology.routingKey(POISON_TYPE),
                    null,
                    POISON_BODY);
            assertThat(poisonReceived.await(10, SECONDS)).isTrue();

            healthyChannel.basicPublish(
                    AmqpTopology.INPUT_EXCHANGE,
                    AmqpTopology.routingKey(HEALTHY_TYPE),
                    null,
                    HEALTHY_BODY);

            try {
                assertThat(healthyReceived.await(5, SECONDS)).isTrue();
                assertThat(poisonDelivery.get()).isEqualTo(POISON_BODY);
                assertThat(healthyDelivery.get()).isEqualTo(HEALTHY_BODY);
                assertThat(releasePoison.getCount()).isEqualTo(1);
            } finally {
                releasePoison.countDown();
            }
        }
    }

    private static void declarePerTypeTopology(
            Channel channel, StatType firstType, StatType secondType) throws Exception {
        ExchangeDefinition exchange = AmqpTopology.inputExchange();
        channel.exchangeDeclare(
                exchange.name(), exchange.type(), exchange.durable(), exchange.autoDelete(), null);
        declareAndBind(channel, firstType);
        declareAndBind(channel, secondType);
    }

    private static void declareAndBind(Channel channel, StatType type) throws Exception {
        QueueDefinition queue = AmqpTopology.mainQueueDefinition(type);
        channel.queueDeclare(
                queue.name(),
                queue.durable(),
                queue.exclusive(),
                queue.autoDelete(),
                queue.arguments());
        channel.queueBind(queue.name(), AmqpTopology.INPUT_EXCHANGE, AmqpTopology.routingKey(type));
    }

    private static ExecResult exec(String... command) throws Exception {
        return RabbitSupport.container().execInContainer(command);
    }

    private static void assertCommandSucceeded(ExecResult result) {
        assertThat(result.getExitCode())
                .withFailMessage(
                        "RabbitMQ command failed:%nstdout:%n%s%nstderr:%n%s",
                        result.getStdout(), result.getStderr())
                .isZero();
    }
}
