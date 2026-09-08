package com.zenner.datahub.stats.writer.retry;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.testsupport.PostgresSupport;
import com.zenner.datahub.stats.testsupport.RabbitSupport;
import com.zenner.datahub.stats.writer.StatsWriterApplication;
import com.zenner.datahub.stats.writer.consumer.StatsMessageProcessor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@link FailedDeliveryRouter} against a real broker and the real topology (T-024;
 * ADR-002 §4/§12, ADR-005).
 *
 * <p><strong>The router is driven directly, with no listener.</strong> An earlier
 * version published to the main queue and let the consumer fail — and it was flaky, for
 * a reason worth recording: the broker is a JVM singleton shared by every test class,
 * Spring caches contexts, and a listener container left running by another cached
 * context is a competing consumer on the same queue. Messages went missing and, once,
 * one test received another's record. Driving the router removes the race outright, and
 * the consumer's use of it is covered where it belongs — {@code LorastatsConsumerTest}
 * asserts the route-then-ACK order, and
 * {@code LorastatsConsumerIntegrationTest.aProcessingFailureMovesTheRecordToRetryTierOne…}
 * asserts the end state through a real listener.</p>
 *
 * <p>Nothing consumes the retry or dead-letter queues, so what lands there is
 * deterministic. What this class proves is everything a mocked template cannot: that
 * the publish reaches the right queue, that the headers survive the broker, that
 * {@code app_stats_dlx} actually has a route for the bounce-back key, and that the DLQ
 * accepts a message without expiring it.</p>
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
class RetryAndDeadLetterIntegrationTest {

    private static final StatType TYPE = StatType.LORASTATS;
    private static final String MAIN_QUEUE = AmqpTopology.mainQueue(TYPE);
    private static final String DLQ = AmqpTopology.deadLetterQueue(TYPE);
    private static final byte[] BODY = "{\"uid\":\"retry-arm-proof\",\"type\":\"lorastats\"}".getBytes(UTF_8);

    /**
     * Present only to keep the listener from starting: its {@code autoStartup} asks
     * whether a processor for this type exists, and a mock that answers nothing keeps
     * this context's consumer out of the way of its own assertions.
     */
    @MockitoBean private StatsMessageProcessor processor;

    @Autowired private FailedDeliveryRouter router;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private AmqpAdmin rabbitAdmin;

    @BeforeEach
    void emptyEveryQueue() {
        purgeEverything();
    }

    @AfterEach
    void emptyEveryQueueAgain() {
        purgeEverything();
    }

    /** A delivery that has already been handed on {@code previousAttempts} times. */
    private static Message delivery(int previousAttempts) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        if (previousAttempts > 0) {
            properties.setHeader(FailedDeliveryRouter.ATTEMPT_HEADER, previousAttempts);
        }
        return new Message(BODY, properties);
    }

    @Test
    @DisplayName("a transient failure is held in tier 1, with the body untouched")
    void transientFailureIsHeldInTierOne() {
        router.route(TYPE, delivery(0), new IllegalStateException("database transaction failed"));

        Message held = awaitMessageOn(AmqpTopology.retryQueue(TYPE, 1));
        assertThat(held.getMessageProperties().getHeaders())
                .containsEntry(FailedDeliveryRouter.ATTEMPT_HEADER, 1)
                .containsEntry(FailedDeliveryRouter.FAILURE_HEADER, "IllegalStateException");
        // ADR-004 has to survive the retry loop: a redriven record must still collide on
        // (ts_device, uid) when it finally lands.
        assertThat(held.getBody()).isEqualTo(BODY);
        assertThat(held.getMessageProperties().getReceivedDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
    }

    @Test
    @DisplayName("the fourth failure retires the record to the DLQ, not to a fourth tier")
    void exhaustedAttemptsReachTheDeadLetterQueue() {
        // Arriving with three attempts already on it is what a record looks like coming
        // back from tier 3. Waiting out the real delays would take 35½ minutes, and
        // shortening them here would redeclare the tier queues with different arguments
        // than every other context on this shared broker — the RESOURCE_LOCKED trap
        // T-032 exists for, met by the test proving the arm. It happened once already.
        router.route(TYPE, delivery(3), new IllegalStateException("database transaction failed"));

        Message dead = awaitMessageOn(DLQ);
        assertThat(dead.getMessageProperties().getHeaders())
                .containsEntry(FailedDeliveryRouter.ATTEMPT_HEADER, 4)
                .containsEntry(FailedDeliveryRouter.PERMANENT_HEADER, false);
        assertThat(dead.getBody()).isEqualTo(BODY);
    }

    @Test
    @DisplayName("a permanent failure skips the tiers and is dead-lettered on the first attempt")
    void permanentFailureIsDeadLetteredImmediately() {
        router.route(TYPE, delivery(0), new DataIntegrityViolationException("clsbox check violated"));

        Message dead = awaitMessageOn(DLQ);
        assertThat(dead.getMessageProperties().getHeaders())
                .containsEntry(FailedDeliveryRouter.ATTEMPT_HEADER, 1)
                .containsEntry(FailedDeliveryRouter.PERMANENT_HEADER, true)
                .containsEntry(
                        FailedDeliveryRouter.FAILURE_HEADER, "DataIntegrityViolationException");
        assertThat(dead.getBody()).isEqualTo(BODY);
        // Nothing went near a retry tier: 35½ minutes of queue time not spent, and at
        // 115.2m records/day a systematic permanent failure not tripled.
        for (int tier = 1; tier <= AmqpTopology.RETRY_TIERS; tier++) {
            assertThat(rabbitTemplate.receive(AmqpTopology.retryQueue(TYPE, tier), 200)).isNull();
        }
    }

    @Test
    @DisplayName("a payload rejection reaches the DLQ on its first attempt, body intact")
    void aPayloadRejectionIsDeadLetteredImmediately() {
        // T-057's end state. PermanentFailureException is the channel the payload gate
        // and the dialect range-check throw on, and this asserts what the broker actually
        // does with it: straight to the DLQ, marked permanent, with the record byte-for
        // -byte as Heimdal sent it so a redrive after the schema is fixed still collides
        // on (ts_device, uid).
        router.route(TYPE, delivery(0), new PermanentFailureException("Payload failed its per-type schema"));

        Message dead = awaitMessageOn(DLQ);
        assertThat(dead.getMessageProperties().getHeaders())
                .containsEntry(FailedDeliveryRouter.ATTEMPT_HEADER, 1)
                .containsEntry(FailedDeliveryRouter.PERMANENT_HEADER, true)
                .containsEntry(FailedDeliveryRouter.FAILURE_HEADER, "PermanentFailureException");
        assertThat(dead.getBody()).isEqualTo(BODY);
        for (int tier = 1; tier <= AmqpTopology.RETRY_TIERS; tier++) {
            assertThat(rabbitTemplate.receive(AmqpTopology.retryQueue(TYPE, tier), 200)).isNull();
        }
    }

    @Test
    @DisplayName("the dead-letter exchange has a live route for the retry bounce-back key")
    void theBounceBackKeyIsRoutable() {
        // A tier queue dead-letters its expired message to app_stats_dlx under the type's
        // own routing key. If nothing were bound to that key the broker would discard it:
        // the retry arm would look correct, the DLQ would stay reassuringly empty, and
        // every transiently failed record would vanish thirty seconds after it failed.
        //
        // Routability is asserted here rather than by receiving from the main queue,
        // because a listener in another cached context would be a competing consumer.
        // Where that key POINTS is asserted in LorastatsTopologyDeclarationTest.
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbitTemplate.send(
                AmqpTopology.DEAD_LETTER_EXCHANGE,
                AmqpTopology.routingKey(TYPE),
                new Message(BODY, new MessageProperties()),
                correlation);

        await().atMost(ofSeconds(10))
                .untilAsserted(() -> assertThat(correlation.getFuture()).isDone());
        // mandatory=true is set on this template: an unbound key comes back as a return.
        assertThat(correlation.getReturned()).isNull();

        rabbitTemplate.receive(MAIN_QUEUE, 2_000);
        rabbitAdmin.purgeQueue(MAIN_QUEUE, false);
    }

    @Test
    @DisplayName("the DLQ is declared with no expiry and no onward route")
    void deadLetterQueueCannotSilentlyDiscard() {
        // Declaring it again with the same arguments is a no-op; declaring it with
        // DIFFERENT arguments is a broker-level error. This asserts the live queue, not
        // the map we passed — a TTL added by hand on the broker fails here.
        assertThat(rabbitAdmin.getQueueProperties(DLQ)).isNotNull();
        assertThat(AmqpTopology.deadLetterQueueArguments())
                .doesNotContainKey(AmqpTopology.X_MESSAGE_TTL)
                .doesNotContainKey(AmqpTopology.X_DEAD_LETTER_EXCHANGE);
    }

    private Message awaitMessageOn(String queue) {
        Message[] holder = new Message[1];
        await().atMost(ofSeconds(30))
                .untilAsserted(
                        () -> {
                            Message received = rabbitTemplate.receive(queue, 500);
                            if (received != null) {
                                holder[0] = received;
                            }
                            assertThat(holder[0]).isNotNull();
                        });
        return holder[0];
    }

    private int readyMessages(String queue) {
        java.util.Properties properties = rabbitAdmin.getQueueProperties(queue);
        return properties == null ? 0 : (int) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
    }

    private void purgeEverything() {
        List<String> queues =
                List.of(
                        MAIN_QUEUE,
                        AmqpTopology.retryQueue(TYPE, 1),
                        AmqpTopology.retryQueue(TYPE, 2),
                        AmqpTopology.retryQueue(TYPE, 3),
                        DLQ);
        queues.forEach(queue -> rabbitAdmin.purgeQueue(queue, false));
    }
}
