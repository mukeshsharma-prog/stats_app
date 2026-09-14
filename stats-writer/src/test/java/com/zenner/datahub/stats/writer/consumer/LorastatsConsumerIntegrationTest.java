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
import java.util.concurrent.CompletableFuture;
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
            "stats.consumer.lorastats.prefetch=2",
            "stats.consumer.lorastats.shutdown-timeout=1s",
            "stats.retention.schedule=-",
            // These tests create and drop their own partitions on fixed far-future
            // days. The startup pre-creation is exercised by
            // PartitionBootstrapIntegrationTest, which leaves it on.
            "stats.retention.pre-create-on-startup=false"
        })
@Import({PostgresSupport.class, RabbitSupport.class})
class LorastatsConsumerIntegrationTest {

    private static final String QUEUE = AmqpTopology.mainQueue(StatType.LORASTATS);
    private static final String RETRY_TIER_1 = AmqpTopology.retryQueue(StatType.LORASTATS, 1);
    private static final byte[] BODY = "{\"uid\":\"ack-order-proof\"}".getBytes(UTF_8);

    @MockitoBean
    private StatsMessageProcessor processor;

    private final CountDownLatch processingStarted = new CountDownLatch(1);
    private final CountDownLatch allowCompletion = new CountDownLatch(1);
    private final CountDownLatch processingCompleted = new CountDownLatch(1);
    private final CountDownLatch processingInterrupted = new CountDownLatch(1);
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
        // The broker is a JVM singleton shared by every test class. A record left in a
        // retry tier bounces back to the main queue when its TTL expires — 30 seconds
        // later, inside whichever test happens to be running then.
        rabbitAdmin.purgeQueue(RETRY_TIER_1, false);
    }

    @Test
    void consumesOnlyTheDerivedQueueWithManualAcknowledgementAndExternalPrefetch() {
        assertThat(container).isNotNull();
        assertThat(container.getQueueNames()).containsExactly(QUEUE);
        assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
        assertThat(ReflectionTestUtils.getField(container, "prefetchCount")).isEqualTo(2);
        assertThat(ReflectionTestUtils.getField(container, "forceStop")).isEqualTo(true);
        assertThat(ReflectionTestUtils.getField(container, "shutdownTimeout")).isEqualTo(1_000L);
    }

    @Test
    void shutdownFinishesTheCurrentDeliveryAndReturnsPrefetchedWork() throws Exception {
        blockInsideProcessor();
        container.start();

        rabbitTemplate.convertAndSend(
                AmqpTopology.INPUT_EXCHANGE, AmqpTopology.routingKey(StatType.LORASTATS), BODY);
        assertThat(processingStarted.await(10, TimeUnit.SECONDS)).isTrue();

        byte[] waiting = "{\"uid\":\"prefetched-during-shutdown\"}".getBytes(UTF_8);
        rabbitTemplate.convertAndSend(
                AmqpTopology.INPUT_EXCHANGE, AmqpTopology.routingKey(StatType.LORASTATS), waiting);
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(readyMessages()).isZero());

        CompletableFuture<Void> stopped = CompletableFuture.runAsync(container::stop);
        allowCompletion.countDown();

        assertThat(processingCompleted.await(10, TimeUnit.SECONDS)).isTrue();
        stopped.get(10, TimeUnit.SECONDS);
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(readyMessages()).isOne());

        Message returned = rabbitTemplate.receive(QUEUE, 2_000);
        assertThat(returned).isNotNull();
        assertThat(returned.getBody()).isEqualTo(waiting);
    }

    @Test
    void shutdownTimeoutReturnsAnUnacknowledgedCurrentDelivery() throws Exception {
        blockInsideProcessor();
        container.start();

        rabbitTemplate.convertAndSend(
                AmqpTopology.INPUT_EXCHANGE, AmqpTopology.routingKey(StatType.LORASTATS), BODY);
        assertThat(processingStarted.await(10, TimeUnit.SECONDS)).isTrue();

        container.stop();

        assertThat(processorReturned).isFalse();
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(readyMessages()).isOne());
        assertThat(processingInterrupted.await(10, TimeUnit.SECONDS)).isTrue();
        await().during(java.time.Duration.ofMillis(300))
                .atMost(ofSeconds(5))
                .untilAsserted(
                        () -> {
                            assertThat(readyMessages()).isOne();
                            assertThat(readyMessages(RETRY_TIER_1)).isZero();
                        });
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
     * The other half of the T-020 contract, as T-024 changed it: a processing failure
     * moves the record to retry tier 1 and acknowledges the original <em>only then</em>.
     *
     * <p><strong>This test asserted the opposite until 5 Sep 2026</strong>, and the old
     * assertion was correct for the code that existed: without a failure arm, a failed
     * delivery stayed unacknowledged and the broker requeued it when the channel closed.
     * Nothing was lost, but nothing progressed either — the same record was redelivered
     * forever and only {@code records_write_failed} said so. With the arm in place the
     * record leaves the main queue, waits its tier delay, and comes back for a bounded
     * number of attempts (DL-122).</p>
     *
     * <p>The order is the load-bearing part and it is asserted as an end state: the main
     * queue is empty (the original was ACKed) <em>and</em> tier 1 holds the record (the
     * copy exists). ACK-then-publish would produce the same main-queue reading with an
     * empty tier 1 for as long as the gap lasted (ADR-005).</p>
     */
    @Test
    void aProcessingFailureMovesTheRecordToRetryTierOneBeforeAcknowledgingIt()
            throws Exception {
        doThrow(new IllegalStateException("database transaction failed"))
                .when(processor)
                .process(eq(StatType.LORASTATS), any(Message.class));
        container.start();

        rabbitTemplate.convertAndSend(
                AmqpTopology.INPUT_EXCHANGE, AmqpTopology.routingKey(StatType.LORASTATS), BODY);

        // Tier 1 holds it for 30 s here — the production delay, not a shortened one — so
        // there is no race between the copy landing and this assertion.
        await().atMost(ofSeconds(15))
                .untilAsserted(() -> assertThat(readyMessages(RETRY_TIER_1)).isEqualTo(1));

        container.stop();
        // Empty AFTER the stop is the acknowledgement: anything still unacknowledged on
        // the channel would have been requeued when it closed.
        await().atMost(ofSeconds(10)).untilAsserted(() -> assertThat(readyMessages()).isZero());
    }

    private void blockInsideProcessor() throws Exception {
        doAnswer(invocation -> {
                    assertThat(invocation.getArgument(0, StatType.class))
                            .isEqualTo(StatType.LORASTATS);
                    assertThat(new String(invocation.getArgument(1, Message.class).getBody(), UTF_8))
                            .isEqualTo(new String(BODY, UTF_8));
                    processingStarted.countDown();
                    try {
                        assertThat(allowCompletion.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException shutdown) {
                        processingInterrupted.countDown();
                        throw shutdown;
                    }
                    processorReturned.set(true);
                    processingCompleted.countDown();
                    return null;
                })
                .when(processor)
                .process(eq(StatType.LORASTATS), any(Message.class));
    }

    /** Messages READY on the queue. Excludes deliveries that are out and unacknowledged. */
    private int readyMessages() {
        return readyMessages(QUEUE);
    }

    /** As above, for any queue in the type's topology. */
    private int readyMessages(String queue) {
        Properties properties = rabbitAdmin.getQueueProperties(queue);
        assertThat(properties).isNotNull();
        return (int) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
    }

    private int consumers() {
        Properties properties = rabbitAdmin.getQueueProperties(QUEUE);
        assertThat(properties).isNotNull();
        return (int) properties.get(RabbitAdmin.QUEUE_CONSUMER_COUNT);
    }
}
