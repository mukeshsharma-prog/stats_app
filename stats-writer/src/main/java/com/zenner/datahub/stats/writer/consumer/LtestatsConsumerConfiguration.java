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

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LtestatsConsumerProperties.class)
public class LtestatsConsumerConfiguration {

    @Bean
    Queue ltestatsMainQueue() {
        return WriterAmqpConfiguration.queue(
                AmqpTopology.mainQueueDefinition(LtestatsConsumerDescriptor.TYPE));
    }

    @Bean
    Binding ltestatsMainBinding(Queue ltestatsMainQueue, TopicExchange statsInputExchange) {
        return BindingBuilder.bind(ltestatsMainQueue)
                .to(statsInputExchange)
                .with(AmqpTopology.routingKey(LtestatsConsumerDescriptor.TYPE));
    }

    @Bean
    Binding ltestatsRetryReturnBinding(
            Queue ltestatsMainQueue, TopicExchange statsDeadLetterExchange) {
        return BindingBuilder.bind(ltestatsMainQueue)
                .to(statsDeadLetterExchange)
                .with(AmqpTopology.routingKey(LtestatsConsumerDescriptor.TYPE));
    }

    @Bean
    Queue ltestatsRetryTier1Queue(WriterAmqpProperties properties) {
        return retryQueue(1, properties);
    }

    @Bean
    Queue ltestatsRetryTier2Queue(WriterAmqpProperties properties) {
        return retryQueue(2, properties);
    }

    @Bean
    Queue ltestatsRetryTier3Queue(WriterAmqpProperties properties) {
        return retryQueue(3, properties);
    }

    @Bean
    Queue ltestatsDeadLetterQueue() {
        return WriterAmqpConfiguration.queue(
                AmqpTopology.deadLetterQueueDefinition(LtestatsConsumerDescriptor.TYPE));
    }

    @Bean
    Binding ltestatsDeadLetterBinding(
            Queue ltestatsDeadLetterQueue, TopicExchange statsDeadLetterExchange) {
        return BindingBuilder.bind(ltestatsDeadLetterQueue)
                .to(statsDeadLetterExchange)
                .with(AmqpTopology.deadLetterRoutingKey(LtestatsConsumerDescriptor.TYPE));
    }

    private static Queue retryQueue(int tier, WriterAmqpProperties properties) {
        return WriterAmqpConfiguration.queue(
                AmqpTopology.retryQueueDefinition(
                        LtestatsConsumerDescriptor.TYPE, tier, properties.delayForTier(tier)));
    }

    @Bean(name = LtestatsConsumer.CONTAINER_FACTORY)
    SimpleRabbitListenerContainerFactory ltestatsRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            LtestatsConsumerProperties properties) {
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

    @Bean
    @Conditional(LtestatsDeploymentCondition.class)
    LtestatsConsumer ltestatsConsumer(
            StatsMessageProcessorAvailability processorAvailability,
            FailedDeliveryRouter failedDeliveryRouter,
            MeterRegistry registry,
            QueueAgeMetrics queueAge) {
        return new LtestatsConsumer(
                processorAvailability, failedDeliveryRouter, registry, queueAge);
    }
}
