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

class LorastatsConsumerConfigurationTest {

    private final LorastatsConsumerConfiguration configuration =
            new LorastatsConsumerConfiguration();

    @Test
    void derivesTheDedicatedDurableQueueAndBindingFromSharedTopology() {
        TopicExchange exchange = new WriterAmqpConfiguration().statsInputExchange();
        Queue queue = configuration.lorastatsMainQueue();
        Binding binding = configuration.lorastatsMainBinding(queue, exchange);

        assertThat(exchange.getName()).isEqualTo(AmqpTopology.INPUT_EXCHANGE);
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
        assertThat(queue.getName()).isEqualTo(AmqpTopology.mainQueue(StatType.LORASTATS));
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.isExclusive()).isFalse();
        assertThat(queue.isAutoDelete()).isFalse();
        assertThat(queue.getArguments()).isEqualTo(AmqpTopology.mainQueueArguments());
        assertThat(binding.getDestination()).isEqualTo(queue.getName());
        assertThat(binding.getExchange()).isEqualTo(exchange.getName());
        assertThat(binding.getRoutingKey())
                .isEqualTo(AmqpTopology.routingKey(StatType.LORASTATS));
    }

    @Test
    void appliesExternalConcurrencyAndPrefetchWithManualAcknowledgement() {
        SimpleRabbitListenerContainerFactoryConfigurer configurer =
                mock(SimpleRabbitListenerContainerFactoryConfigurer.class);
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        LorastatsConsumerProperties properties =
                new LorastatsConsumerProperties(3, 321, Duration.ofSeconds(17));

        SimpleRabbitListenerContainerFactory factory =
                configuration.lorastatsRabbitListenerContainerFactory(
                        configurer, connectionFactory, properties);
        verify(configurer).configure(factory, connectionFactory);
        factory.setConnectionFactory(connectionFactory);

        SimpleRabbitListenerEndpoint endpoint = new SimpleRabbitListenerEndpoint();
        endpoint.setId("configuration-proof");
        endpoint.setQueueNames(AmqpTopology.mainQueue(StatType.LORASTATS));
        endpoint.setMessageListener(message -> {});
        SimpleMessageListenerContainer container = factory.createListenerContainer(endpoint);

        assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
        assertThat(ReflectionTestUtils.getField(container, "concurrentConsumers")).isEqualTo(3);
        assertThat(ReflectionTestUtils.getField(container, "maxConcurrentConsumers")).isEqualTo(3);
        assertThat(ReflectionTestUtils.getField(container, "prefetchCount")).isEqualTo(321);
        assertThat(ReflectionTestUtils.getField(container, "forceStop")).isEqualTo(true);
        assertThat(ReflectionTestUtils.getField(container, "shutdownTimeout")).isEqualTo(17_000L);
    }

    @Test
    void listenerAnnotationUsesTheDedicatedQueueBeanAndManualMode() throws Exception {
        Method consume =
                LorastatsConsumer.class.getMethod(
                        "consume",
                        org.springframework.amqp.core.Message.class,
                        com.rabbitmq.client.Channel.class);
        RabbitListener listener = consume.getAnnotation(RabbitListener.class);

        assertThat(listener.id()).isEqualTo(LorastatsConsumer.LISTENER_ID);
        assertThat(listener.queues()).containsExactly("#{@lorastatsMainQueue.name}");
        assertThat(listener.containerFactory())
                .isEqualTo(LorastatsConsumer.CONTAINER_FACTORY);
        assertThat(listener.ackMode()).isEqualTo("MANUAL");
        // Selection is by stat type, not by "the only processor bean" -- see DL-097.
        assertThat(listener.autoStartup())
                .isEqualTo("#{@statsMessageProcessorAvailability.available('lorastats') && ${spring.rabbitmq.listener.simple.auto-startup:true}}");
    }

    @Test
    void rejectsZeroOrNegativeConsumerControls() {
        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            var validator = validatorFactory.getValidator();

            assertThat(validator.validate(
                            new LorastatsConsumerProperties(1, 500, Duration.ofSeconds(5))))
                    .isEmpty();
            assertThat(propertyNames(
                            validator.validate(
                                    new LorastatsConsumerProperties(
                                            0, -1, Duration.ofSeconds(5)))))
                    .containsExactlyInAnyOrder("concurrency", "prefetch");
        }
    }

    @Test
    void rejectsANonPositiveShutdownTimeout() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new LorastatsConsumerProperties(1, 500, Duration.ZERO))
                .withMessage("stats.consumer.lorastats.shutdown-timeout must be positive");
    }

    private static Set<String> propertyNames(
            Set<jakarta.validation.ConstraintViolation<LorastatsConsumerProperties>> violations) {
        return violations.stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }
}
