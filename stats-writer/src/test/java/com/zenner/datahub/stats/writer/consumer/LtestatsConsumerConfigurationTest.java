package com.zenner.datahub.stats.writer.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.model.StatType;
import jakarta.validation.Validation;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.test.util.ReflectionTestUtils;

class LtestatsConsumerConfigurationTest {

    private final LtestatsConsumerConfiguration configuration =
            new LtestatsConsumerConfiguration();

    @Test
    void derivesTheDedicatedDurableQueueAndBindingFromSharedTopology() {
        TopicExchange exchange = new WriterAmqpConfiguration().statsInputExchange();
        Queue queue = configuration.ltestatsMainQueue();
        Binding binding = configuration.ltestatsMainBinding(queue, exchange);

        assertThat(queue.getName()).isEqualTo(AmqpTopology.mainQueue(StatType.LTESTATS));
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.isExclusive()).isFalse();
        assertThat(queue.isAutoDelete()).isFalse();
        assertThat(queue.getArguments()).isEqualTo(AmqpTopology.mainQueueArguments());
        assertThat(binding.getDestination()).isEqualTo(queue.getName());
        assertThat(binding.getExchange()).isEqualTo(exchange.getName());
        assertThat(binding.getRoutingKey())
                .isEqualTo(AmqpTopology.routingKey(StatType.LTESTATS));
        // Its own queue, not the reference slice's.
        assertThat(queue.getName()).isNotEqualTo(AmqpTopology.mainQueue(StatType.LORASTATS));
    }

    @Test
    void appliesExternalConcurrencyAndPrefetchWithManualAcknowledgement() {
        SimpleRabbitListenerContainerFactoryConfigurer configurer =
                mock(SimpleRabbitListenerContainerFactoryConfigurer.class);
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        LtestatsConsumerProperties properties =
                new LtestatsConsumerProperties(2, 250, Duration.ofSeconds(9));

        SimpleRabbitListenerContainerFactory factory =
                configuration.ltestatsRabbitListenerContainerFactory(
                        configurer, connectionFactory, properties);
        verify(configurer).configure(factory, connectionFactory);
        factory.setConnectionFactory(connectionFactory);

        SimpleRabbitListenerEndpoint endpoint = new SimpleRabbitListenerEndpoint();
        endpoint.setId("configuration-proof");
        endpoint.setQueueNames(AmqpTopology.mainQueue(StatType.LTESTATS));
        endpoint.setMessageListener(message -> {});
        SimpleMessageListenerContainer container = factory.createListenerContainer(endpoint);

        assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
        assertThat(ReflectionTestUtils.getField(container, "concurrentConsumers")).isEqualTo(2);
        assertThat(ReflectionTestUtils.getField(container, "maxConcurrentConsumers")).isEqualTo(2);
        assertThat(ReflectionTestUtils.getField(container, "prefetchCount")).isEqualTo(250);
        assertThat(ReflectionTestUtils.getField(container, "forceStop")).isEqualTo(true);
        assertThat(ReflectionTestUtils.getField(container, "shutdownTimeout")).isEqualTo(9_000L);
    }

    @Test
    void listenerAnnotationUsesTheDedicatedQueueBeanAndManualMode() throws Exception {
        Method consume =
                LtestatsConsumer.class.getMethod(
                        "consume",
                        org.springframework.amqp.core.Message.class,
                        com.rabbitmq.client.Channel.class);
        RabbitListener listener = consume.getAnnotation(RabbitListener.class);

        assertThat(listener.id()).isEqualTo(LtestatsConsumer.LISTENER_ID).isNotEqualTo(LorastatsConsumer.LISTENER_ID);
        assertThat(listener.queues()).containsExactly("#{@ltestatsMainQueue.name}");
        assertThat(listener.containerFactory())
                .isEqualTo(LtestatsConsumer.CONTAINER_FACTORY)
                .isNotEqualTo(LorastatsConsumer.CONTAINER_FACTORY);
        assertThat(listener.ackMode()).isEqualTo("MANUAL");
        assertThat(listener.autoStartup())
                .isEqualTo("#{@statsMessageProcessorAvailability.available('ltestats') && ${spring.rabbitmq.listener.simple.auto-startup:true}}");
    }

    @Test
    void rejectsZeroOrNegativeConsumerControls() {
        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            var validator = validatorFactory.getValidator();

            assertThat(validator.validate(
                            new LtestatsConsumerProperties(1, 500, Duration.ofSeconds(5))))
                    .isEmpty();
            assertThat(propertyNames(
                            validator.validate(
                                    new LtestatsConsumerProperties(
                                            0, -1, Duration.ofSeconds(5)))))
                    .containsExactlyInAnyOrder("concurrency", "prefetch");
        }
    }

    @Test
    void rejectsANonPositiveShutdownTimeout() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new LtestatsConsumerProperties(1, 500, Duration.ZERO))
                .withMessage("stats.consumer.ltestats.shutdown-timeout must be positive");
    }

    private static Set<String> propertyNames(
            Set<jakarta.validation.ConstraintViolation<LtestatsConsumerProperties>> violations) {
        return violations.stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }
}
