package com.zenner.datahub.stats.writer.consumer;

import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.amqp.AmqpTopology.ExchangeDefinition;
import com.zenner.datahub.stats.common.amqp.AmqpTopology.QueueDefinition;
import com.zenner.datahub.stats.writer.metrics.QueueAgeMetrics;
import com.zenner.datahub.stats.writer.retry.FailedDeliveryRouter;
import io.micrometer.core.instrument.MeterRegistry;
import com.zenner.datahub.stats.writer.retry.WriterAmqpProperties;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;

/**
 * RabbitMQ declaration and listener configuration for the lorastats reference slice.
 *
 * <p><strong>Every declaration is built from an {@link AmqpTopology} definition</strong>
 * rather than from remembered flags (T-032). {@code stats-input} declares the same
 * ingress exchange, and RabbitMQ does not merge two declarations of one resource — it
 * rejects the second with {@code RESOURCE_LOCKED} and that module fails to start.
 * Durability and auto-delete are as fatal as the arguments; they used to live only in
 * two constructor calls in two modules. {@code LorastatsTopologyDeclarationTest} and
 * its counterpart in {@code stats-input} both check theirs with the same
 * {@code AmqpDeclarationAssert}.</p>
 *
 * <p><strong>The full failure arm is here (T-024).</strong> Three retry tiers, a DLQ,
 * and the dead-letter exchange that carries both — separated by routing key, because
 * one key returns a record for another attempt and the other retires it. Sharing a key
 * would turn every dead-letter into an infinite loop.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LorastatsConsumerProperties.class)
public class LorastatsConsumerConfiguration {

    @Bean
    TopicExchange statsInputExchange() {
        return topicExchange(AmqpTopology.inputExchange());
    }

    /**
     * Carries two different journeys, told apart by routing key: an expired retry
     * message bounces back to the main queue under the type's own key, and a retired
     * record lands in the DLQ under {@code <type>.dlq} (ADR-002 §4).
     */
    @Bean
    TopicExchange statsDeadLetterExchange() {
        return topicExchange(AmqpTopology.deadLetterExchange());
    }

    @Bean
    Queue lorastatsMainQueue() {
        return queue(AmqpTopology.mainQueueDefinition(LorastatsConsumerDescriptor.TYPE));
    }

    @Bean
    Binding lorastatsMainBinding(Queue lorastatsMainQueue, TopicExchange statsInputExchange) {
        return BindingBuilder.bind(lorastatsMainQueue)
                .to(statsInputExchange)
                .with(AmqpTopology.routingKey(LorastatsConsumerDescriptor.TYPE));
    }

    /**
     * The bounce-back binding, and the reason the retry tiers work at all.
     *
     * <p>A tier queue holds a message for its TTL and then dead-letters it to
     * {@link AmqpTopology#DEAD_LETTER_EXCHANGE} under the type's routing key. Without
     * this binding that message is unroutable and the broker discards it — the retry
     * arm would look correct, the DLQ would stay empty, and every transiently failed
     * record would silently disappear 30 seconds after it failed.</p>
     */
    @Bean
    Binding lorastatsRetryReturnBinding(
            Queue lorastatsMainQueue, TopicExchange statsDeadLetterExchange) {
        return BindingBuilder.bind(lorastatsMainQueue)
                .to(statsDeadLetterExchange)
                .with(AmqpTopology.routingKey(LorastatsConsumerDescriptor.TYPE));
    }

    @Bean
    Queue lorastatsRetryTier1Queue(WriterAmqpProperties properties) {
        return retryQueue(1, properties);
    }

    @Bean
    Queue lorastatsRetryTier2Queue(WriterAmqpProperties properties) {
        return retryQueue(2, properties);
    }

    @Bean
    Queue lorastatsRetryTier3Queue(WriterAmqpProperties properties) {
        return retryQueue(3, properties);
    }

    /**
     * No expiry and no onward route, by construction (ADR-002 §12). A TTL here is
     * silent data loss: a record that reached this queue has survived three tiers and
     * is the only remaining copy of something Heimdal believes it delivered.
     */
    @Bean
    Queue lorastatsDeadLetterQueue() {
        return queue(AmqpTopology.deadLetterQueueDefinition(LorastatsConsumerDescriptor.TYPE));
    }

    @Bean
    Binding lorastatsDeadLetterBinding(
            Queue lorastatsDeadLetterQueue, TopicExchange statsDeadLetterExchange) {
        return BindingBuilder.bind(lorastatsDeadLetterQueue)
                .to(statsDeadLetterExchange)
                .with(AmqpTopology.deadLetterRoutingKey(LorastatsConsumerDescriptor.TYPE));
    }

    private static Queue retryQueue(int tier, WriterAmqpProperties properties) {
        return queue(
                AmqpTopology.retryQueueDefinition(
                        LorastatsConsumerDescriptor.TYPE, tier, properties.delayForTier(tier)));
    }

    /** The four lines that turn a shared definition into a Spring AMQP object. */
    private static TopicExchange topicExchange(ExchangeDefinition definition) {
        return new TopicExchange(definition.name(), definition.durable(), definition.autoDelete());
    }

    /** As above, for queues. Both are what {@code AmqpDeclarationAssert} checks. */
    private static Queue queue(QueueDefinition definition) {
        return new Queue(
                definition.name(),
                definition.durable(),
                definition.exclusive(),
                definition.autoDelete(),
                definition.arguments());
    }

    @Bean(name = LorastatsConsumer.CONTAINER_FACTORY)
    SimpleRabbitListenerContainerFactory lorastatsRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            LorastatsConsumerProperties properties) {
        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(properties.concurrency());
        factory.setMaxConcurrentConsumers(properties.concurrency());
        factory.setPrefetchCount(properties.prefetch());
        // Finish only the deliveries already executing. Anything merely prefetched is
        // returned to RabbitMQ instead of extending shutdown by an entire prefetch window.
        factory.setForceStop(true);
        factory.setContainerCustomizer(
                container ->
                        container.setShutdownTimeout(properties.shutdownTimeout().toMillis()));
        return factory;
    }

    @Bean
    StatsMessageProcessorAvailability statsMessageProcessorAvailability(
            ObjectProvider<StatsMessageProcessor> processors) {
        return new StatsMessageProcessorAvailability(processors);
    }

    /**
     * The listener starts only when the T-021/T-022 processing pipeline is present.
     * Starting a no-op consumer would ACK and discard records before persistence,
     * the exact silent-loss window T-020 is intended to close.
     */
    @Bean
    @Conditional(LorastatsDeploymentCondition.class)
    LorastatsConsumer lorastatsConsumer(
            StatsMessageProcessorAvailability processorAvailability,
            FailedDeliveryRouter failedDeliveryRouter,
            MeterRegistry registry,
            QueueAgeMetrics queueAge) {
        return new LorastatsConsumer(
                processorAvailability, failedDeliveryRouter, registry, queueAge);
    }
}
