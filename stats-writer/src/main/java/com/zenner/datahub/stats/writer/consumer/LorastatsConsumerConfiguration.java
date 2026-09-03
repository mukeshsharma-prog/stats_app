package com.zenner.datahub.stats.writer.consumer;

import com.zenner.datahub.stats.common.amqp.AmqpTopology;
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

/** RabbitMQ declaration and listener configuration for the lorastats reference slice. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LorastatsConsumerProperties.class)
public class LorastatsConsumerConfiguration {

    @Bean
    TopicExchange statsInputExchange() {
        return new TopicExchange(AmqpTopology.INPUT_EXCHANGE, true, false);
    }

    @Bean
    Queue lorastatsMainQueue() {
        return new Queue(
                AmqpTopology.mainQueue(LorastatsConsumerDescriptor.TYPE),
                true,
                false,
                false,
                AmqpTopology.mainQueueArguments());
    }

    @Bean
    Binding lorastatsMainBinding(Queue lorastatsMainQueue, TopicExchange statsInputExchange) {
        return BindingBuilder.bind(lorastatsMainQueue)
                .to(statsInputExchange)
                .with(AmqpTopology.routingKey(LorastatsConsumerDescriptor.TYPE));
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
            StatsMessageProcessorAvailability processorAvailability) {
        return new LorastatsConsumer(processorAvailability);
    }
}
