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
@EnableConfigurationProperties(NetstatsConsumerProperties.class)
public class NetstatsConsumerConfiguration {

    @Bean
    Queue netstatsMainQueue() {
        return WriterAmqpConfiguration.queue(
                AmqpTopology.mainQueueDefinition(NetstatsConsumerDescriptor.TYPE));
    }

    @Bean
    Binding netstatsMainBinding(Queue netstatsMainQueue, TopicExchange statsInputExchange) {
        return BindingBuilder.bind(netstatsMainQueue)
                .to(statsInputExchange)
                .with(AmqpTopology.routingKey(NetstatsConsumerDescriptor.TYPE));
    }

    @Bean
    Binding netstatsRetryReturnBinding(
            Queue netstatsMainQueue, TopicExchange statsDeadLetterExchange) {
        return BindingBuilder.bind(netstatsMainQueue)
                .to(statsDeadLetterExchange)
                .with(AmqpTopology.routingKey(NetstatsConsumerDescriptor.TYPE));
    }

    @Bean
    Queue netstatsRetryTier1Queue(WriterAmqpProperties properties) {
        return retryQueue(1, properties);
    }

    @Bean
    Queue netstatsRetryTier2Queue(WriterAmqpProperties properties) {
        return retryQueue(2, properties);
    }

    @Bean
    Queue netstatsRetryTier3Queue(WriterAmqpProperties properties) {
        return retryQueue(3, properties);
    }

    @Bean
    Queue netstatsDeadLetterQueue() {
        return WriterAmqpConfiguration.queue(
                AmqpTopology.deadLetterQueueDefinition(NetstatsConsumerDescriptor.TYPE));
    }

    @Bean
    Binding netstatsDeadLetterBinding(
            Queue netstatsDeadLetterQueue, TopicExchange statsDeadLetterExchange) {
        return BindingBuilder.bind(netstatsDeadLetterQueue)
                .to(statsDeadLetterExchange)
                .with(AmqpTopology.deadLetterRoutingKey(NetstatsConsumerDescriptor.TYPE));
    }

    private static Queue retryQueue(int tier, WriterAmqpProperties properties) {
        return WriterAmqpConfiguration.queue(
                AmqpTopology.retryQueueDefinition(
                        NetstatsConsumerDescriptor.TYPE, tier, properties.delayForTier(tier)));
    }

    @Bean(name = NetstatsConsumer.CONTAINER_FACTORY)
    SimpleRabbitListenerContainerFactory netstatsRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            NetstatsConsumerProperties properties) {
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

    @Bean
    @Conditional(NetstatsDeploymentCondition.class)
    NetstatsConsumer netstatsConsumer(
            StatsMessageProcessorAvailability processorAvailability,
            FailedDeliveryRouter failedDeliveryRouter,
            MeterRegistry registry,
            QueueAgeMetrics queueAge) {
        return new NetstatsConsumer(
                processorAvailability, failedDeliveryRouter, registry, queueAge);
    }
}
