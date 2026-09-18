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
 * T-032 for the second type: every sysstats declaration matches the shared definition,
 * and a sysstats deployment still declares the two shared exchanges — from
 * {@link WriterAmqpConfiguration}, not from the lorastats file.
 */
@SpringBootTest(
        classes = StatsWriterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
        properties = {
            "stats.writer.stat-type=sysstats",
            "stats.retention.schedule=-",
            "stats.retention.pre-create-on-startup=false"
        })
@Import({PostgresSupport.class, RabbitSupport.class})
class SysstatsTopologyDeclarationTest {

    private static final StatType TYPE = StatType.SYSSTATS;

    @Autowired private ApplicationContext context;
    @Autowired private WriterAmqpProperties amqpProperties;

    @Test
    @DisplayName("a sysstats deployment declares both shared exchanges")
    void sharedExchangesArePresentInASysstatsDeployment() {
        AmqpDeclarationAssert.assertMatches(
                context.getBean("statsInputExchange", TopicExchange.class),
                AmqpTopology.inputExchange());
        AmqpDeclarationAssert.assertMatches(
                context.getBean("statsDeadLetterExchange", TopicExchange.class),
                AmqpTopology.deadLetterExchange());
        assertThat(context.getBeansOfType(TopicExchange.class))
                .as("one declaration of each exchange, never two")
                .hasSize(2);
    }

    @Test
    @DisplayName("the main queue matches the shared definition, quorum arguments included")
    void mainQueueMatchesTheSharedDefinition() {
        AmqpDeclarationAssert.assertMatches(
                context.getBean("sysstatsMainQueue", Queue.class),
                AmqpTopology.mainQueueDefinition(TYPE));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    @DisplayName("each retry tier carries its configured delay as the queue's TTL")
    void retryTiersMatchTheSharedDefinition(int tier) {
        AmqpDeclarationAssert.assertMatches(
                context.getBean("sysstatsRetryTier" + tier + "Queue", Queue.class),
                AmqpTopology.retryQueueDefinition(TYPE, tier, amqpProperties.delayForTier(tier)));
    }

    @Test
    @DisplayName("the DLQ matches the shared definition — no TTL, no onward route")
    void deadLetterQueueMatchesTheSharedDefinition() {
        AmqpDeclarationAssert.assertMatches(
                context.getBean("sysstatsDeadLetterQueue", Queue.class),
                AmqpTopology.deadLetterQueueDefinition(TYPE));
    }

    @Test
    @DisplayName("the retry bounce-back and the dead-letter route use DIFFERENT keys")
    void theTwoDeadLetterExchangeRoutesAreDistinct() {
        Binding retryReturn = context.getBean("sysstatsRetryReturnBinding", Binding.class);
        Binding deadLetter = context.getBean("sysstatsDeadLetterBinding", Binding.class);

        assertThat(retryReturn.getRoutingKey()).isEqualTo(AmqpTopology.routingKey(TYPE));
        assertThat(retryReturn.getDestination()).isEqualTo(AmqpTopology.mainQueue(TYPE));
        assertThat(deadLetter.getRoutingKey()).isEqualTo(AmqpTopology.deadLetterRoutingKey(TYPE));
        assertThat(deadLetter.getDestination()).isEqualTo(AmqpTopology.deadLetterQueue(TYPE));
        assertThat(retryReturn.getRoutingKey()).isNotEqualTo(deadLetter.getRoutingKey());
        assertThat(retryReturn.getExchange())
                .isEqualTo(deadLetter.getExchange())
                .isEqualTo(AmqpTopology.DEAD_LETTER_EXCHANGE);
    }

    @Test
    @DisplayName("the lorastats queues are still declared — the two slices share nothing but the exchanges")
    void theOtherTypesQueuesAreUntouched() {
        // Declarations are unconditional so the topology exists whichever type is
        // deployed first (DL-123); only the CONSUMER is conditional.
        assertThat(context.containsBean("lorastatsMainQueue")).isTrue();
        assertThat(context.containsBean("lorastatsConsumer")).isFalse();
        assertThat(context.containsBean("sysstatsConsumer")).isTrue();
    }
}
