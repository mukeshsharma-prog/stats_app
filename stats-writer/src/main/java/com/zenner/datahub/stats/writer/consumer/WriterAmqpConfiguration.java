package com.zenner.datahub.stats.writer.consumer;

import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.amqp.AmqpTopology.ExchangeDefinition;
import com.zenner.datahub.stats.common.amqp.AmqpTopology.QueueDefinition;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The broker declarations every stat type shares, declared once.
 *
 * <p>Until the second type arrived these three beans lived in
 * {@code LorastatsConsumerConfiguration}, which was harmless while it was the only
 * per-type configuration and wrong the moment there were two: a sibling that redeclared
 * {@code statsInputExchange} would fail the context on a bean-name clash, and one that
 * did not would silently depend on the lorastats file continuing to exist. Rule 6 says a
 * new type is additive; that is only true if nothing a new type needs is owned by an
 * existing one. So the exchanges and the processor selector live here, and each
 * {@code <Type>ConsumerConfiguration} declares exactly its own queues, bindings,
 * container factory and consumer.</p>
 *
 * <p>Every declaration is still built from an {@link AmqpTopology} definition,
 * through the two helpers at the bottom, so the durability and auto-delete flags cannot
 * drift between modules and RabbitMQ never sees two spellings of one resource.</p>
 */
@Configuration(proxyBeanMethods = false)
public class WriterAmqpConfiguration {

    @Bean
    TopicExchange statsInputExchange() {
        return topicExchange(AmqpTopology.inputExchange());
    }

    /**
     * Carries two different journeys, told apart by routing key: an expired retry
     * message bounces back to its main queue under the type's own key, and a retired
     * record lands in the DLQ under {@code <type>.dlq}.
     */
    @Bean
    TopicExchange statsDeadLetterExchange() {
        return topicExchange(AmqpTopology.deadLetterExchange());
    }

    /**
     * Selection is by stat type, never "the only processor bean": with five
     * processors registered, resolving by uniqueness would throw on the second one and
     * adding a type would break the existing ones.
     */
    @Bean
    StatsMessageProcessorAvailability statsMessageProcessorAvailability(
            ObjectProvider<StatsMessageProcessor> processors) {
        return new StatsMessageProcessorAvailability(processors);
    }

    /** The four lines that turn a shared definition into a Spring AMQP object. */
    static TopicExchange topicExchange(ExchangeDefinition definition) {
        return new TopicExchange(definition.name(), definition.durable(), definition.autoDelete());
    }

    /** As above, for queues. Both are what {@code AmqpDeclarationAssert} checks. */
    static Queue queue(QueueDefinition definition) {
        return new Queue(
                definition.name(),
                definition.durable(),
                definition.exclusive(),
                definition.autoDelete(),
                definition.arguments());
    }
}
