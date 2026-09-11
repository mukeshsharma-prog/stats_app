package com.zenner.datahub.stats.writer.redrive;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.rabbitmq.client.ConnectionFactory;
import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.testsupport.PostgresSupport;
import com.zenner.datahub.stats.testsupport.RabbitSupport;
import com.zenner.datahub.stats.writer.StatsWriterApplication;
import com.zenner.datahub.stats.writer.consumer.StatsMessageProcessor;
import com.zenner.datahub.stats.writer.retry.FailedDeliveryRouter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
        classes = StatsWriterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "stats.writer.stat-type=lorastats",
            "stats.retention.schedule=-",
            "stats.retention.pre-create-on-startup=false"
        })
@Import({PostgresSupport.class, RabbitSupport.class})
class DlqRedriveIntegrationTest {
    private static final StatType TYPE = StatType.LORASTATS;
    private static final String MAIN_QUEUE = AmqpTopology.mainQueue(TYPE);
    private static final String DLQ = AmqpTopology.deadLetterQueue(TYPE);

    @MockitoBean private StatsMessageProcessor processor;

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private AmqpAdmin rabbitAdmin;

    @BeforeEach
    void emptyQueues() {
        purgeEverything();
    }

    @AfterEach
    void emptyQueuesAgain() {
        purgeEverything();
    }

    @Test
    void movesTheOriginalBytesToTheMainQueueForOneManualAttempt() throws Exception {
        byte[] body = ("{\"uid\":\"CLSCON-redrive-proof\",\"type\":\"lorastats\","
                        + "\"ts\":{\"device\":"
                        + Instant.now().getEpochSecond()
                        + "}}")
                .getBytes(UTF_8);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId("CLSCON-redrive-proof");
        properties.setHeader(FailedDeliveryRouter.ATTEMPT_HEADER, 4);
        properties.setHeader(FailedDeliveryRouter.FAILURE_HEADER, "IllegalStateException");
        properties.setHeader(FailedDeliveryRouter.PERMANENT_HEADER, false);
        rabbitTemplate.send("", DLQ, new Message(body, properties));

        String user = "redrive_" + UUID.randomUUID().toString().replace("-", "");
        String password = UUID.randomUUID().toString();
        createRestrictedRedriveUser(user, password);
        try {
            ConnectionFactory client = new ConnectionFactory();
            client.setHost(RabbitSupport.container().getHost());
            client.setPort(RabbitSupport.container().getAmqpPort());
            client.setVirtualHost("/");
            client.setUsername(user);
            client.setPassword(password);
            try (var connection = client.newConnection(); var channel = connection.createChannel()) {
                int moved = DlqRedriveCommand.redrive(
                        channel,
                        new DlqRedriveCommand.Options(
                                TYPE,
                                1,
                                DlqRedriveCommand.MAX_RATE,
                                90,
                                5_000,
                                "INC-123",
                                true));
                assertThat(moved).isEqualTo(1);
            }
        } finally {
            assertThat(RabbitSupport.container().execInContainer("rabbitmqctl", "delete_user", user)
                            .getExitCode())
                    .isZero();
        }

        Message redriven = rabbitTemplate.receive(MAIN_QUEUE, 500);
        boolean processedByCompetingListener = redriven == null;
        if (processedByCompetingListener) {
            redriven = awaitMessageOn(DLQ);
        }
        assertThat(redriven).isNotNull();
        assertThat(redriven.getBody()).isEqualTo(body);
        assertThat(redriven.getMessageProperties().getHeaders())
                .doesNotContainKey("x-death")
                .containsEntry(FailedDeliveryRouter.MANUAL_REDRIVE_HEADER, true)
                .containsEntry("x-stats-redrive-previous-attempt", 4)
                .containsEntry("x-stats-redrive-previous-failure", "IllegalStateException")
                .containsEntry("x-stats-redrive-change-id", "INC-123");
        if (processedByCompetingListener) {
            assertThat(redriven.getMessageProperties().getHeaders())
                    .containsEntry(FailedDeliveryRouter.ATTEMPT_HEADER, 5)
                    .containsEntry(FailedDeliveryRouter.PERMANENT_HEADER, true)
                    .doesNotContainEntry(
                            FailedDeliveryRouter.FAILURE_HEADER, "IllegalStateException");
        } else {
            assertThat(redriven.getMessageProperties().getHeaders())
                    .containsEntry(FailedDeliveryRouter.ATTEMPT_HEADER, 4)
                    .containsEntry(FailedDeliveryRouter.PERMANENT_HEADER, false)
                    .containsEntry(
                            FailedDeliveryRouter.FAILURE_HEADER, "IllegalStateException");
            assertThat(rabbitTemplate.receive(DLQ, 200)).isNull();
        }
        for (int tier = 1; tier <= AmqpTopology.RETRY_TIERS; tier++) {
            assertThat(rabbitTemplate.receive(AmqpTopology.retryQueue(TYPE, tier), 200)).isNull();
        }
    }

    private static void createRestrictedRedriveUser(String user, String password) throws Exception {
        assertThat(RabbitSupport.container()
                        .execInContainer("rabbitmqctl", "add_user", user, password)
                        .getExitCode())
                .isZero();
        assertThat(RabbitSupport.container()
                        .execInContainer(
                                "rabbitmqctl",
                                "set_permissions",
                                "-p",
                                "/",
                                user,
                                "^(app_statsstore\\.lorastats|app_statsstore\\.lorastats\\.dlq)$",
                                "^app_stats_input$",
                                "^app_statsstore\\.lorastats\\.dlq$")
                        .getExitCode())
                .isZero();
        assertThat(RabbitSupport.container()
                        .execInContainer(
                                "rabbitmqctl",
                                "set_topic_permissions",
                                "-p",
                                "/",
                                user,
                                AmqpTopology.INPUT_EXCHANGE,
                                "^lorastats$",
                                "^$")
                        .getExitCode())
                .isZero();
    }

    private Message awaitMessageOn(String queue) {
        Message[] holder = new Message[1];
        await().atMost(ofSeconds(10))
                .untilAsserted(
                        () -> {
                            Message received = rabbitTemplate.receive(queue, 200);
                            if (received != null) {
                                holder[0] = received;
                            }
                            assertThat(holder[0]).isNotNull();
                        });
        return holder[0];
    }

    private void purgeEverything() {
        List.of(
                        MAIN_QUEUE,
                        AmqpTopology.retryQueue(TYPE, 1),
                        AmqpTopology.retryQueue(TYPE, 2),
                        AmqpTopology.retryQueue(TYPE, 3),
                        DLQ)
                .forEach(queue -> rabbitAdmin.purgeQueue(queue, false));
    }
}
