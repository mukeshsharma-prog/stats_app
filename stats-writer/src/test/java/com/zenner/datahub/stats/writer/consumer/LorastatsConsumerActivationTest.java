package com.zenner.datahub.stats.writer.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LorastatsConsumerActivationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(LorastatsConsumerConfiguration.class)
            .withBean(
                    SimpleRabbitListenerContainerFactoryConfigurer.class,
                    () -> mock(SimpleRabbitListenerContainerFactoryConfigurer.class))
            .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
            .withPropertyValues(
                    "stats.consumer.lorastats.concurrency=1",
                    "stats.consumer.lorastats.prefetch=500");

    @Test
    void doesNotStartANoOpConsumerBeforeTheProcessingPipelineExists() {
        contextRunner.withPropertyValues("stats.writer.stat-type=lorastats").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LorastatsConsumer.class);
            assertThat(context.getBean(StatsMessageProcessorAvailability.class).available())
                    .isFalse();
        });
    }

    @Test
    void createsTheConsumerWhenLorastatsAndARealProcessorAreConfigured() {
        contextRunner
                .withPropertyValues("stats.writer.stat-type=lorastats")
                .withBean(
                        StatsMessageProcessor.class,
                        () -> (expectedType, message) -> {})
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LorastatsConsumer.class);
                    assertThat(context.getBean(StatsMessageProcessorAvailability.class).available())
                            .isTrue();
                });
    }

    @Test
    void anotherProductionStatTypeCannotActivateTheLorastatsConsumer() {
        contextRunner
                .withPropertyValues("stats.writer.stat-type=ltestats")
                .withBean(
                        StatsMessageProcessor.class,
                        () -> (expectedType, message) -> {})
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(LorastatsConsumer.class);
                });
    }
}
