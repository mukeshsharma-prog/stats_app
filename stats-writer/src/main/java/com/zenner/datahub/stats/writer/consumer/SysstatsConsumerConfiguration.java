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
 * RabbitMQ declaration and listener configuration for the sysstats slice (T-035,
 * DL-148; STA-10A, STA-11A, STA-14A).
 *
 * <p>A sibling of {@code LorastatsConsumerConfiguration}, declaring exactly this type's
 * resources — main queue, three retry tiers, DLQ, the four bindings and the container
 * factory — from the same {@link AmqpTopology} definitions (T-032). The two exchanges and
 * the processor selector are shared and live in {@link WriterAmqpConfiguration}; nothing
 * here is declared twice and nothing here depends on another type's file.</p>
 *
 * <p>The retry bounce-back binding is not decoration (see the lorastats sibling): a tier
 * queue dead-letters an expired message to {@link AmqpTopology#DEAD_LETTER_EXCHANGE} under
 * this type's routing key, and without a binding for that key the broker discards it
 * silently. Same key, different exchange, different meaning.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SysstatsConsumerProperties.class)
public class SysstatsConsumerConfiguration {

    @Bean
    Queue sysstatsMainQueue() {
        return WriterAmqpConfiguration.queue(
                AmqpTopology.mainQueueDefinition(SysstatsConsumerDescriptor.TYPE));
    }

    @Bean
    Binding sysstatsMainBinding(Queue sysstatsMainQueue, TopicExchange statsInputExchange) {
        return BindingBuilder.bind(sysstatsMainQueue)
                .to(statsInputExchange)
                .with(AmqpTopology.routingKey(SysstatsConsumerDescriptor.TYPE));
    }

    @Bean
    Binding sysstatsRetryReturnBinding(
            Queue sysstatsMainQueue, TopicExchange statsDeadLetterExchange) {
        return BindingBuilder.bind(sysstatsMainQueue)
                .to(statsDeadLetterExchange)
                .with(AmqpTopology.routingKey(SysstatsConsumerDescriptor.TYPE));
    }

    @Bean
    Queue sysstatsRetryTier1Queue(WriterAmqpProperties properties) {
        return retryQueue(1, properties);
    }

    @Bean
    Queue sysstatsRetryTier2Queue(WriterAmqpProperties properties) {
        return retryQueue(2, properties);
    }

    @Bean
    Queue sysstatsRetryTier3Queue(WriterAmqpProperties properties) {
        return retryQueue(3, properties);
    }

    /** No expiry and no onward route, by construction (ADR-002 §12). */
    @Bean
    Queue sysstatsDeadLetterQueue() {
        return WriterAmqpConfiguration.queue(
                AmqpTopology.deadLetterQueueDefinition(SysstatsConsumerDescriptor.TYPE));
    }

    @Bean
    Binding sysstatsDeadLetterBinding(
            Queue sysstatsDeadLetterQueue, TopicExchange statsDeadLetterExchange) {
        return BindingBuilder.bind(sysstatsDeadLetterQueue)
                .to(statsDeadLetterExchange)
                .with(AmqpTopology.deadLetterRoutingKey(SysstatsConsumerDescriptor.TYPE));
    }

    private static Queue retryQueue(int tier, WriterAmqpProperties properties) {
        return WriterAmqpConfiguration.queue(
                AmqpTopology.retryQueueDefinition(
                        SysstatsConsumerDescriptor.TYPE, tier, properties.delayForTier(tier)));
    }

    @Bean(name = SysstatsConsumer.CONTAINER_FACTORY)
    SimpleRabbitListenerContainerFactory sysstatsRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            SysstatsConsumerProperties properties) {
        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(properties.concurrency());
        factory.setMaxConcurrentConsumers(properties.concurrency());
        factory.setPrefetchCount(properties.prefetch());
        // Finish only the deliveries already executing; return the prefetched rest to
        // RabbitMQ instead of extending shutdown by a whole prefetch window (DL-143).
        factory.setForceStop(true);
        factory.setContainerCustomizer(
                container ->
                        container.setShutdownTimeout(properties.shutdownTimeout().toMillis()));
        return factory;
    }

    /**
     * The listener starts only when the sysstats processing pipeline is present. A
     * no-op consumer would ACK and discard records before persistence.
     */
    @Bean
    @Conditional(SysstatsDeploymentCondition.class)
    SysstatsConsumer sysstatsConsumer(
            StatsMessageProcessorAvailability processorAvailability,
            FailedDeliveryRouter failedDeliveryRouter,
            MeterRegistry registry,
            QueueAgeMetrics queueAge) {
        return new SysstatsConsumer(
                processorAvailability, failedDeliveryRouter, registry, queueAge);
    }
}
