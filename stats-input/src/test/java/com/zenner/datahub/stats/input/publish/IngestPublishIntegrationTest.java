package com.zenner.datahub.stats.input.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.testsupport.AmqpDeclarationAssert;
import com.zenner.datahub.stats.input.StatsInputApplication;
import com.zenner.datahub.stats.input.ingest.IngestMetrics;
import com.zenner.datahub.stats.testsupport.RabbitSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The publish half of "2xx only after a publisher confirm", against a real broker:
 * confirm before 2xx, an unroutable message is a failed acceptance, and byte-identical
 * publication.
 *
 * <p>All three ride one context and one container because all three are the same
 * question asked three ways — <em>is a 200 from this endpoint true?</em></p>
 */
@SpringBootTest(classes = StatsInputApplication.class)
@AutoConfigureMockMvc
@Import(RabbitSupport.class)
@TestPropertySource(
        properties = {
            "STATS_INPUT_AUTH_MODE=none",
            "STATS_INGEST_ROLE=stats.ingest",
            "stats.amqp.confirm-timeout=5s"
        })
class IngestPublishIntegrationTest {

    /** Formatting no serializer would reproduce — see {@link #publicationIsByteIdentical()}. */
    private static final String ADVERSARIAL_FIXTURE =
            "byte-identity/lorastats-adversarial-formatting.json";

    private static final String LORASTATS_QUEUE = AmqpTopology.mainQueue(StatType.LORASTATS);

    @Autowired private MockMvc mockMvc;
    @Autowired private AmqpAdmin amqpAdmin;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private TopicExchange statsInputExchange;
    @Autowired private org.springframework.context.ApplicationContext context;

    /**
     * Only {@code lorastats} gets a queue. The other four types stay deliberately
     * unbound: that is not an incomplete fixture, it is the topology state the unroutable check
     * exists for — a type deployed before its queue.
     */
    @BeforeEach
    void bindLorastatsOnly() {
        Queue queue = new Queue(LORASTATS_QUEUE, true, false, false, AmqpTopology.mainQueueArguments());
        amqpAdmin.declareQueue(queue);
        Binding binding =
                BindingBuilder.bind(queue)
                        .to(statsInputExchange)
                        .with(AmqpTopology.routingKey(StatType.LORASTATS));
        amqpAdmin.declareBinding(binding);
        amqpAdmin.purgeQueue(LORASTATS_QUEUE, false);
    }

    @AfterEach
    void emptyTheQueue() {
        amqpAdmin.purgeQueue(LORASTATS_QUEUE, false);
    }

    @Test
    @DisplayName("a confirmed publish is what earns the 2xx, and the record is on the queue")
    void confirmedPublishEarnsTheSuccessStatus() throws Exception {
        MvcResult response =
                mockMvc.perform(postRecord(ADVERSARIAL_FIXTURE)).andExpect(status().isOk()).andReturn();

        Message published = receive();
        assertThat(published).isNotNull();
        // A durable queue holds a transient message exactly as long
        // as the broker process lives.
        assertThat(published.getMessageProperties().getReceivedDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(published.getMessageProperties().getContentType())
                .isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(published.getMessageProperties().getReceivedRoutingKey())
                .isEqualTo(StatType.LORASTATS.wireName());
        assertThat(published.getMessageProperties().getMessageId())
                .isEqualTo("CLSCON-11d3ec4c-f6e1-403a-a9ef-b6f5fe909eee");
        assertThat(published.getMessageProperties().getCorrelationId())
                .isEqualTo(response.getResponse().getHeader("X-Trace-Id"));
    }

    @Test
    @DisplayName("what is published is byte-for-byte what was received")
    void publicationIsByteIdentical() throws Exception {
        // The fixture is built to FAIL under any re-serialisation and pass under none:
        // keys out of canonical order, mixed indentation, a é escape that Jackson
        // would emit as a literal, a BIGINT-maximum integer, and no trailing newline.
        // A well-formed fixture would round-trip through Jackson unchanged and this
        // assertion would pass on a publisher that re-encodes every record.
        //
        // It lives here rather than in docs/schemas/fixtures because it is a
        // SERIALISATION fixture, not a schema-conformance one: the normative set is
        // gated by validate-fixtures.py on count and MANIFEST entry, and a file whose
        // whole purpose is its byte layout has no business in a set that could
        // reasonably be reformatted.
        //
        // This is what makes the Writer's ON CONFLICT (ts_device, uid) DO
        // NOTHING correct — a redelivered record has to BE the same record.
        byte[] sent = fixture(ADVERSARIAL_FIXTURE);

        mockMvc.perform(postRecord(ADVERSARIAL_FIXTURE)).andExpect(status().isOk());

        Message published = receive();
        assertThat(published).isNotNull();
        assertThat(published.getBody()).isEqualTo(sent);
    }

    @Test
    @DisplayName("an unroutable record is a failed acceptance: 503, never a 2xx")
    void unroutableRecordIsAnsweredWith503() throws Exception {
        // sysstats has no queue and no binding in this context. The broker will ACK the
        // publish and drop the message; without mandatory=true and this check, Input
        // would answer 200 to a record that no longer exists anywhere.
        double unroutableBefore = counter(IngestMetrics.UNROUTABLE, StatType.SYSSTATS);
        double failedBefore = counter(IngestMetrics.PUBLISH_FAILED, StatType.SYSSTATS);

        mockMvc.perform(postRecord("valid/sysstats-production-minimal.json"))
                .andExpect(status().isServiceUnavailable());

        assertThat(counter(IngestMetrics.UNROUTABLE, StatType.SYSSTATS))
                .isEqualTo(unroutableBefore + 1);
        // Cause and outcome both move. Their ratio is what separates "a queue is
        // missing" from "the broker is down" — the same 503 to Heimdal, two different
        // incidents to us.
        assertThat(counter(IngestMetrics.PUBLISH_FAILED, StatType.SYSSTATS))
                .isEqualTo(failedBefore + 1);
    }

    @Test
    @DisplayName("Input declares the exchange exactly as the Writer does, and declares no queues")
    void declaresTheExchangeAndNothingElse() {
        // The declaration-conflict check, stats-input half. The assertion implementation is shared with
        // LorastatsTopologyDeclarationTest, so the two modules are checked against one
        // definition by one piece of code — a mismatch in either is a build failure
        // rather than a RESOURCE_LOCKED startup failure in whichever environment boots
        // them in the wrong order.
        AmqpDeclarationAssert.assertMatches(statsInputExchange, AmqpTopology.inputExchange());

        // And no queues. Declaring them here would create undrained queues for types
        // whose Writer is not deployed, and would make the missing-binding case — the
        // entire reason mandatory is set — unreachable.
        assertThat(context.getBeansOfType(Queue.class)).isEmpty();
    }

    private Message receive() {
        return rabbitTemplate.receive(LORASTATS_QUEUE, 5_000);
    }

    private double counter(String name, StatType statType) {
        return meterRegistry.find(name).tag("type", statType.wireName()).counter() == null
                ? 0.0d
                : meterRegistry.find(name).tag("type", statType.wireName()).counter().count();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            postRecord(String fixture) {
        return post("/internal/stats").contentType(MediaType.APPLICATION_JSON).content(fixture(fixture));
    }

    private static byte[] fixture(String name) {
        try (InputStream in =
                IngestPublishIntegrationTest.class.getClassLoader().getResourceAsStream(name)) {
            return Objects.requireNonNull(in, "fixture not on the test classpath: " + name)
                    .readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("could not read fixture " + name, e);
        }
    }
}
