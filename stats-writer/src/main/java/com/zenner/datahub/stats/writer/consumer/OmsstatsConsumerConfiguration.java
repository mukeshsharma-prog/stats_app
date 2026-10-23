package com.zenner.datahub.stats.writer.consumer;

import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.writer.metrics.QueueAgeMetrics;
import com.zenner.datahub.stats.writer.retry.FailedDeliveryRouter;
import com.zenner.datahub.stats.writer.retry.WriterAmqpProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ declaration and listener configuration for the omsstats slice.
 *
 * <p>A sibling of {@code SysstatsConsumerConfiguration}, declaring exactly this type's
 * resources — main queue, three retry tiers, DLQ, the four bindings and the container
 * factory — from the same {@link AmqpTopology} definitions. The two exchanges and
 * the processor selector are shared and live in {@link WriterAmqpConfiguration}; nothing
 * here is declared twice and nothing here depends on another type's file.</p>
 *
 * <p>The retry bounce-back binding is not decoration (see the lorastats sibling's javadoc): a tier
 * queue dead-letters an expired message to {@link AmqpTopology#DEAD_LETTER_EXCHANGE} under
 * this type's routing key, and without a binding for that key the broker discards it
 * silently. Same key, different exchange, different meaning.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OmsstatsConsumerProperties.class)
public class OmsstatsConsumerConfiguration {

    @Bean
    Queue omsstatsMainQueue() {
        return WriterAmqpConfiguration.queue(
                AmqpTopology.mainQueueDefinition(OmsstatsConsumerDescriptor.TYPE));
    }

    @Bean
    Binding omsstatsMainBinding(Queue omsstatsMainQueue, TopicExchange statsInputExchange) {
        return BindingBuilder.bind(omsstatsMainQueue)
                .to(statsInputExchange)
                .with(AmqpTopology.routingKey(OmsstatsConsumerDescriptor.TYPE));
    }

    @Bean
    Binding omsstatsRetryReturnBinding(
            Queue omsstatsMainQueue, TopicExchange statsDeadLetterExchange) {
        return BindingBuilder.bind(omsstatsMainQueue)
                .to(statsDeadLetterExchange)
                .with(AmqpTopology.routingKey(OmsstatsConsumerDescriptor.TYPE));
    }

    @Bean
    Queue omsstatsRetryTier1Queue(WriterAmqpProperties properties) {
        return retryQueue(1, properties);
    }

    @Bean
    Queue omsstatsRetryTier2Queue(WriterAmqpProperties properties) {
        return retryQueue(2, properties);
    }

    @Bean
    Queue omsstatsRetryTier3Queue(WriterAmqpProperties properties) {
        return retryQueue(3, properties);
    }

    /** No expiry and no onward route, by construction. */
    @Bean
    Queue omsstatsDeadLetterQueue() {
        return WriterAmqpConfiguration.queue(
                AmqpTopology.deadLetterQueueDefinition(OmsstatsConsumerDescriptor.TYPE));
    }

    @Bean
    Binding omsstatsDeadLetterBinding(
            Queue omsstatsDeadLetterQueue, TopicExchange statsDeadLetterExchange) {
        return BindingBuilder.bind(omsstatsDeadLetterQueue)
                .to(statsDeadLetterExchange)
                .with(AmqpTopology.deadLetterRoutingKey(OmsstatsConsumerDescriptor.TYPE));
    }

    private static Queue retryQueue(int tier, WriterAmqpProperties properties) {
        return WriterAmqpConfiguration.queue(
                AmqpTopology.retryQueueDefinition(
                        OmsstatsConsumerDescriptor.TYPE, tier, properties.delayForTier(tier)));
    }

    @Bean(name = OmsstatsConsumer.CONTAINER_FACTORY)
    SimpleRabbitListenerContainerFactory omsstatsRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            OmsstatsConsumerProperties properties) {
        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(properties.concurrency());
        factory.setMaxConcurrentConsumers(properties.concurrency());
        factory.setPrefetchCount(properties.prefetch());
        // Finish only the deliveries already executing; return the prefetched rest to
        // RabbitMQ instead of extending shutdown by a whole prefetch window.
        factory.setForceStop(true);
        factory.setContainerCustomizer(
                container ->
                        container.setShutdownTimeout(properties.shutdownTimeout().toMillis()));
        return factory;
    }

    /**
     * The listener starts only when the omsstats processing pipeline is present. A
     * no-op consumer would ACK and discard records before persistence.
     */
    @Bean
    @Conditional(OmsstatsDeploymentCondition.class)
    OmsstatsConsumer omsstatsConsumer(
            StatsMessageProcessorAvailability processorAvailability,
            FailedDeliveryRouter failedDeliveryRouter,
            MeterRegistry registry,
            QueueAgeMetrics queueAge) {
        return new OmsstatsConsumer(
                processorAvailability, failedDeliveryRouter, registry, queueAge);
    }
}
