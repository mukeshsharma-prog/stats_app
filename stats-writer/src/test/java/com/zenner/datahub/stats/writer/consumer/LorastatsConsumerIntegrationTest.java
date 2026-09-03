package com.zenner.datahub.stats.writer.consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.testsupport.PostgresSupport;
import com.zenner.datahub.stats.testsupport.RabbitSupport;
import com.zenner.datahub.stats.writer.StatsWriterApplication;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
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
            "stats.retention.schedule=-",
            // These tests create and drop their own partitions on fixed far-future
            // days. The startup pre-creation is exercised by
            // PartitionBootstrapIntegrationTest, which leaves it on.
            "stats.retention.pre-create-on-startup=false"
        })
@Import({PostgresSupport.class, RabbitSupport.class})
class LorastatsConsumerIntegrationTest {

    private static final String QUEUE = AmqpTopology.mainQueue(StatType.LORASTATS);
    private static final byte[] BODY = "{\"uid\":\"ack-order-proof\"}".getBytes(UTF_8);

    @MockitoBean
    private StatsMessageProcessor processor;

    private final CountDownLatch processingStarted = new CountDownLatch(1);
    private final CountDownLatch allowCompletion = new CountDownLatch(1);
    private final CountDownLatch processingCompleted = new CountDownLatch(1);
    private final AtomicBoolean processorReturned = new AtomicBoolean();

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin rabbitAdmin;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    private AbstractMessageListenerContainer container;

    @BeforeEach
    void prepareBlockingProcessorAndEmptyQueue() {
        // The mock cannot answer statType() until now, so the listener's autoStartup
        // SpEL saw no processor at refresh. Starting it here is deliberate and keeps
        // activation itself covered where it belongs, in LorastatsConsumerActivationTest.
        when(processor.statType()).thenReturn(StatType.LORASTATS);
        container =
                (AbstractMessageListenerContainer)
                        listenerRegistry.getListenerContainer(LorastatsConsumer.LISTENER_ID);
        rabbitAdmin.purgeQueue(QUEUE, false);
    }

    @AfterEach
    void releaseProcessorAndStopListener() {
        allowCompletion.countDown();
        if (container != null) {
            container.stop();
        }
        rabbitAdmin.purgeQueue(QUEUE, false);
    }

    @Test
    void consumesOnlyTheDerivedQueueWithManualAcknowledgementAndExternalPrefetch() {
        assertThat(container).isNotNull();
        assertThat(container.getQueueNames()).containsExactly(QUEUE);
        assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
        assertThat(ReflectionTestUtils.getField(container, "prefetchCount")).isEqualTo(1);
    }

    /**
     * The ACK boundary, proved by the only assertion that can fail if the
     * {@code basicAck} is removed.
     *
     * <p>Queue <em>message count</em> cannot prove it: {@code messageCount} on
     * {@code queue.declare-ok} is messages <strong>ready</strong>, which excludes
     * deliveries already handed to a consumer. It reads zero the instant the message
     * is delivered, acknowledged or not. Stopping the container closes the channel,
     * and the broker requeues everything still unacknowledged on it — so a ready
     * count of zero <em>after the stop</em> is the acknowledgement.</p>
     */
    @Test
    void acknowledgesOnlyAfterTheProcessorReturns() throws Exception {
        blockInsideProcessor();
        container.start();

        rabbitTemplate.convertAndSend(
                AmqpTopology.INPUT_EXCHANGE, AmqpTopology.routingKey(StatType.LORASTATS), BODY);

        assertThat(processingStarted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(processorReturned).isFalse();
        // Delivered but unacknowledged, and the ready count already reads zero. This is
        // exactly why the assertion below has to come after a container stop.
        assertThat(readyMessages()).isZero();

        allowCompletion.countDown();
        assertThat(processingCompleted.await(10, TimeUnit.SECONDS)).isTrue();

        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(consumers()).isEqualTo(1));
        container.stop();

        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(readyMessages()).isZero());
    }

    /**
     * The other half of the T-020 contract: a processing failure must not acknowledge,
     * so the broker keeps the only copy of the record.
     *
     * <p>This is also the honest record of today's limitation. Spring AMQP in
     * {@code MANUAL} mode does not {@code basicNack} unless the exception is an
     * {@code AmqpRejectAndDontRequeueException}, so the delivery stays unacknowledged
     * and holds a prefetch slot until the channel closes. Nothing is lost — proved
     * here — but disposition is T-024's retry/DLQ path, and until that lands
     * {@code records_write_failed} is how the situation becomes visible.</p>
     */
    @Test
    void aProcessingFailureLeavesTheRecordWithTheBrokerRatherThanAcknowledgingIt()
            throws Exception {
        doThrow(new IllegalStateException("database transaction failed"))
                .when(processor)
                .process(eq(StatType.LORASTATS), any(Message.class));
        container.start();

        rabbitTemplate.convertAndSend(
                AmqpTopology.INPUT_EXCHANGE, AmqpTopology.routingKey(StatType.LORASTATS), BODY);

        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(consumers()).isEqualTo(1));
        // Give the delivery time to be taken and to fail before closing the channel.
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(readyMessages()).isZero());
        container.stop();

        // Requeued on channel close: unacknowledged is not lost.
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(readyMessages()).isEqualTo(1));
    }

    private void blockInsideProcessor() throws Exception {
        doAnswer(invocation -> {
                    assertThat(invocation.getArgument(0, StatType.class))
                            .isEqualTo(StatType.LORASTATS);
                    assertThat(new String(invocation.getArgument(1, Message.class).getBody(), UTF_8))
                            .isEqualTo(new String(BODY, UTF_8));
                    processingStarted.countDown();
                    assertThat(allowCompletion.await(10, TimeUnit.SECONDS)).isTrue();
                    processorReturned.set(true);
                    processingCompleted.countDown();
                    return null;
                })
                .when(processor)
                .process(eq(StatType.LORASTATS), any(Message.class));
    }

    /** Messages READY on the queue. Excludes deliveries that are out and unacknowledged. */
    private int readyMessages() {
        Properties properties = rabbitAdmin.getQueueProperties(QUEUE);
        assertThat(properties).isNotNull();
        return (int) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
    }

    private int consumers() {
        Properties properties = rabbitAdmin.getQueueProperties(QUEUE);
        assertThat(properties).isNotNull();
        return (int) properties.get(RabbitAdmin.QUEUE_CONSUMER_COUNT);
    }
}
