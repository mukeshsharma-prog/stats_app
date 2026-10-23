package com.zenner.datahub.stats.writer.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.testsupport.AmqpDeclarationAssert;
import com.zenner.datahub.stats.testsupport.PostgresSupport;
import com.zenner.datahub.stats.testsupport.RabbitSupport;
import com.zenner.datahub.stats.writer.StatsWriterApplication;
import com.zenner.datahub.stats.writer.retry.WriterAmqpProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * The declaration-conflict check, Writer's half — every declaration matches the shared definition.
 *
 * <p>{@code stats-input} declares the same ingress exchange and its
 * {@code IngestPublishIntegrationTest} runs the identical assertion from the identical
 * implementation, {@link AmqpDeclarationAssert}. That is what closes the check without
 * either application module depending on the other: the <em>values</em> come from
 * {@code AmqpTopology}, and the four lines that turn a value into a Spring AMQP object
 * are checked on both sides.</p>
 *
 * <p>Why it matters more than it looks: RabbitMQ does not merge two declarations of one
 * resource. It rejects the mismatched one with {@code RESOURCE_LOCKED} and that module
 * fails to start — in whichever environment happens to boot them in the wrong order,
 * which is not usually the one where the change was made. Quorum sharpens it again,
 * because a queue's type is fixed at declaration and correcting one means deleting it.
 * </p>
 *
 * <p>No broker is started here. These are the declarations as this module would send
 * them, and a context is all that is needed to see them.</p>
 */
@SpringBootTest(
        classes = StatsWriterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
        properties = {
            "stats.writer.stat-type=lorastats",
            "stats.retention.schedule=-",
            "stats.retention.pre-create-on-startup=false"
        })
// The shared containers, not because a declaration needs them but because the module's
// real context does: excluding the datasource takes the batch writer with it, and the
// listener connects on startup. Both are JVM singletons other tests have already
// started, so this costs a context, not a container.
@Import({PostgresSupport.class, RabbitSupport.class})
class LorastatsTopologyDeclarationTest {

    private static final StatType TYPE = StatType.LORASTATS;

    @Autowired private ApplicationContext context;
    @Autowired private WriterAmqpProperties amqpProperties;

    @Test
    @DisplayName("the ingress exchange matches the definition stats-input also builds from")
    void ingressExchangeMatchesTheSharedDefinition() {
        AmqpDeclarationAssert.assertMatches(
                context.getBean("statsInputExchange", TopicExchange.class),
                AmqpTopology.inputExchange());
    }

    @Test
    @DisplayName("the dead-letter exchange matches the shared definition")
    void deadLetterExchangeMatchesTheSharedDefinition() {
        AmqpDeclarationAssert.assertMatches(
                context.getBean("statsDeadLetterExchange", TopicExchange.class),
                AmqpTopology.deadLetterExchange());
    }

    @Test
    @DisplayName("the main queue matches the shared definition, quorum arguments included")
    void mainQueueMatchesTheSharedDefinition() {
        AmqpDeclarationAssert.assertMatches(
                context.getBean("lorastatsMainQueue", Queue.class),
                AmqpTopology.mainQueueDefinition(TYPE));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    @DisplayName("each retry tier carries its configured delay as the queue's TTL")
    void retryTiersMatchTheSharedDefinition(int tier) {
        // The delay is externalised, so the definition has to be built with the
        // configured value — asserting against a hard-coded 30 s here would pass while
        // the property was ignored.
        AmqpDeclarationAssert.assertMatches(
                context.getBean("lorastatsRetryTier" + tier + "Queue", Queue.class),
                AmqpTopology.retryQueueDefinition(TYPE, tier, amqpProperties.delayForTier(tier)));
    }

    @Test
    @DisplayName("the DLQ matches the shared definition — no TTL, no onward route")
    void deadLetterQueueMatchesTheSharedDefinition() {
        AmqpDeclarationAssert.assertMatches(
                context.getBean("lorastatsDeadLetterQueue", Queue.class),
                AmqpTopology.deadLetterQueueDefinition(TYPE));
    }

    @Test
    @DisplayName("the retry bounce-back and the dead-letter route use DIFFERENT keys")
    void theTwoDeadLetterExchangeRoutesAreDistinct() {
        // One key returns a record to the main queue for another attempt; the other
        // retires it. Sharing a key would turn every dead-letter into an infinite loop,
        // and the topology would still declare cleanly.
        Binding retryReturn = context.getBean("lorastatsRetryReturnBinding", Binding.class);
        Binding deadLetter = context.getBean("lorastatsDeadLetterBinding", Binding.class);

        assertThat(retryReturn.getRoutingKey()).isEqualTo(AmqpTopology.routingKey(TYPE));
        assertThat(retryReturn.getDestination()).isEqualTo(AmqpTopology.mainQueue(TYPE));
        assertThat(deadLetter.getRoutingKey()).isEqualTo(AmqpTopology.deadLetterRoutingKey(TYPE));
        assertThat(deadLetter.getDestination()).isEqualTo(AmqpTopology.deadLetterQueue(TYPE));
        assertThat(retryReturn.getRoutingKey()).isNotEqualTo(deadLetter.getRoutingKey());
        assertThat(retryReturn.getExchange())
                .isEqualTo(deadLetter.getExchange())
                .isEqualTo(AmqpTopology.DEAD_LETTER_EXCHANGE);
    }
}
