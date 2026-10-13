package com.zenner.datahub.stats.writer.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.writer.metrics.QueueAgeMetrics;
import com.zenner.datahub.stats.writer.retry.FailedDeliveryRouter;
import com.zenner.datahub.stats.writer.retry.WriterAmqpProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Rule 6, from the second type's side: the two per-type configurations coexist in one
 * context, each activates only for its own deployment, and neither needs the other.
 */
class SysstatsConsumerActivationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    WriterAmqpConfiguration.class,
                    LorastatsConsumerConfiguration.class,
                    SysstatsConsumerConfiguration.class)
            .withBean(io.micrometer.core.instrument.MeterRegistry.class,
                    io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
            .withBean(QueueAgeMetrics.class, () -> mock(QueueAgeMetrics.class))
            .withBean(
                    SimpleRabbitListenerContainerFactoryConfigurer.class,
                    () -> mock(SimpleRabbitListenerContainerFactoryConfigurer.class))
            .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
            .withBean(
                    WriterAmqpProperties.class,
                    () ->
                            new WriterAmqpProperties(
                                    new WriterAmqpProperties.Retry(
                                            Duration.ofSeconds(30),
                                            Duration.ofMinutes(5),
                                            Duration.ofMinutes(30)),
                                    3,
                                    Duration.ofSeconds(5),
                                    Duration.ofSeconds(5)))
            .withBean(FailedDeliveryRouter.class, () -> mock(FailedDeliveryRouter.class))
            .withPropertyValues(
                    "stats.consumer.lorastats.concurrency=1",
                    "stats.consumer.lorastats.prefetch=500",
                    "stats.consumer.lorastats.shutdown-timeout=5s",
                    "stats.consumer.sysstats.concurrency=1",
                    "stats.consumer.sysstats.prefetch=500",
                    "stats.consumer.sysstats.shutdown-timeout=5s");

    @Test
    void aSysstatsDeploymentCreatesOnlyTheSysstatsConsumer() {
        contextRunner
                .withPropertyValues("stats.writer.stat-type=sysstats")
                .withBean(StatsMessageProcessor.class, () -> TestProcessors.noOp(StatType.SYSSTATS))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SysstatsConsumer.class);
                    assertThat(context).doesNotHaveBean(LorastatsConsumer.class);
                    assertThat(context.getBean(StatsMessageProcessorAvailability.class)
                                    .available(StatType.SYSSTATS.wireName()))
                            .isTrue();
                });
    }

    @Test
    void aLorastatsDeploymentDoesNotCreateTheSysstatsConsumer() {
        contextRunner
                .withPropertyValues("stats.writer.stat-type=lorastats")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SysstatsConsumer.class);
                    assertThat(context).hasSingleBean(LorastatsConsumer.class);
                });
    }

    @Test
    void theDevelopmentAllTypesModeCreatesBothConsumersAndBothQueueSets() {
        contextRunner
                .withPropertyValues("stats.writer.stat-type=all")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SysstatsConsumer.class);
                    assertThat(context).hasSingleBean(LorastatsConsumer.class);
                    // One shared exchange pair, two per-type queue sets, no clash.
                    assertThat(context).hasBean("statsInputExchange");
                    assertThat(context).hasBean("statsDeadLetterExchange");
                    assertThat(context).hasBean("sysstatsMainQueue");
                    assertThat(context).hasBean("sysstatsRetryTier3Queue");
                    assertThat(context).hasBean("sysstatsDeadLetterQueue");
                    assertThat(context).hasBean("lorastatsMainQueue");
                });
    }

    /** The sysstats consumer must never start against a no-op: it would ACK and discard. */
    @Test
    void doesNotStartBeforeItsOwnProcessorExists() {
        contextRunner
                .withPropertyValues("stats.writer.stat-type=sysstats")
                .withBean(StatsMessageProcessor.class, () -> TestProcessors.noOp(StatType.LORASTATS))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SysstatsConsumer.class);
                    StatsMessageProcessorAvailability availability =
                            context.getBean(StatsMessageProcessorAvailability.class);
                    assertThat(availability.available(StatType.SYSSTATS.wireName())).isFalse();
                    assertThat(availability.available(StatType.LORASTATS.wireName())).isTrue();
                });
    }
}
