package com.zenner.datahub.stats.writer.consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.testsupport.PostgresSupport;
import com.zenner.datahub.stats.testsupport.RabbitSupport;
import com.zenner.datahub.stats.writer.StatsWriterApplication;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

/** Real RabbitMQ proof of the isolated queue, listener settings, and ACK boundary. */
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
class LorastatsConsumerIntegrationTest {

    private static final String QUEUE = AmqpTopology.mainQueue(StatType.LORASTATS);

    @MockitoBean
    private StatsMessageProcessor processor;

    private final CountDownLatch processingStarted = new CountDownLatch(1);
    private final CountDownLatch allowCompletion = new CountDownLatch(1);
    private final CountDownLatch processingCompleted = new CountDownLatch(1);
    private final AtomicBoolean processorReturned = new AtomicBoolean();

    @jakarta.annotation.Resource
    private RabbitTemplate rabbitTemplate;

    @jakarta.annotation.Resource
    private AmqpAdmin rabbitAdmin;

    @jakarta.annotation.Resource
    private RabbitListenerEndpointRegistry listenerRegistry;

    @BeforeEach
    void prepareBlockingProcessorAndEmptyQueue() throws Exception {
        rabbitAdmin.purgeQueue(QUEUE, false);
        doAnswer(invocation -> {
                    assertThat(invocation.getArgument(0, StatType.class))
                            .isEqualTo(StatType.LORASTATS);
                    Message message = invocation.getArgument(1, Message.class);
                    assertThat(new String(message.getBody(), UTF_8))
                            .isEqualTo("{\"uid\":\"ack-order-proof\"}");
                    processingStarted.countDown();
                    assertThat(allowCompletion.await(5, TimeUnit.SECONDS)).isTrue();
                    processorReturned.set(true);
                    processingCompleted.countDown();
                    return null;
                })
                .when(processor)
                .process(eq(StatType.LORASTATS), any(Message.class));
    }

    @AfterEach
    void releaseProcessorIfATestFailsEarly() {
        allowCompletion.countDown();
        rabbitAdmin.purgeQueue(QUEUE, false);
    }

    @AfterAll
    void stopListener() {
        listenerRegistry.getListenerContainer(LorastatsConsumer.LISTENER_ID).stop();
    }

    @Test
    void consumesOnlyTheDerivedQueueAndAcknowledgesAfterProcessingCompletes()
            throws Exception {
        AbstractMessageListenerContainer container =
                (AbstractMessageListenerContainer)
                        listenerRegistry.getListenerContainer(LorastatsConsumer.LISTENER_ID);
        assertThat(container).isNotNull();
        assertThat(container.getQueueNames()).containsExactly(QUEUE);
        assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
        assertThat(ReflectionTestUtils.getField(container, "prefetchCount")).isEqualTo(1);

        rabbitTemplate.convertAndSend(
                AmqpTopology.INPUT_EXCHANGE,
                AmqpTopology.routingKey(StatType.LORASTATS),
                "{\"uid\":\"ack-order-proof\"}".getBytes(UTF_8));

        assertThat(processingStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(processorReturned).isFalse();

        allowCompletion.countDown();
        assertThat(processingCompleted.await(5, TimeUnit.SECONDS)).isTrue();

        await().atMost(ofSeconds(5)).untilAsserted(() -> {
            Properties properties = rabbitAdmin.getQueueProperties(QUEUE);
            assertThat(properties)
                    .containsEntry(
                            org.springframework.amqp.rabbit.core.RabbitAdmin.QUEUE_MESSAGE_COUNT,
                            0)
                    .containsEntry(
                            org.springframework.amqp.rabbit.core.RabbitAdmin.QUEUE_CONSUMER_COUNT,
                            1);
        });
    }
}
