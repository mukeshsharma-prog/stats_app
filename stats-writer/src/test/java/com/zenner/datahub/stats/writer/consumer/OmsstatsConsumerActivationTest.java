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
 * Rule 6, from the third type's side: the three per-type configurations coexist in one
 * context, each activates only for its own deployment, and none needs another.
 */
class OmsstatsConsumerActivationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    WriterAmqpConfiguration.class,
                    LorastatsConsumerConfiguration.class,
                    SysstatsConsumerConfiguration.class,
                    OmsstatsConsumerConfiguration.class)
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
                                    Duration.ofSeconds(5)))
            .withBean(FailedDeliveryRouter.class, () -> mock(FailedDeliveryRouter.class))
            .withPropertyValues(
                    "stats.consumer.lorastats.concurrency=1",
                    "stats.consumer.lorastats.prefetch=500",
                    "stats.consumer.lorastats.shutdown-timeout=5s",
                    "stats.consumer.sysstats.concurrency=1",
                    "stats.consumer.sysstats.prefetch=500",
                    "stats.consumer.sysstats.shutdown-timeout=5s",
                    "stats.consumer.omsstats.concurrency=1",
                    "stats.consumer.omsstats.prefetch=500",
                    "stats.consumer.omsstats.shutdown-timeout=5s");

    @Test
    void anOmsstatsDeploymentCreatesOnlyTheOmsstatsConsumer() {
        contextRunner
                .withPropertyValues("stats.writer.stat-type=omsstats")
                .withBean(StatsMessageProcessor.class, () -> TestProcessors.noOp(StatType.OMSSTATS))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OmsstatsConsumer.class);
                    assertThat(context).doesNotHaveBean(LorastatsConsumer.class);
                    assertThat(context).doesNotHaveBean(SysstatsConsumer.class);
                    assertThat(context.getBean(StatsMessageProcessorAvailability.class)
                                    .available(StatType.OMSSTATS.wireName()))
                            .isTrue();
                });
    }

    @Test
    void aLorastatsDeploymentDoesNotCreateTheOmsstatsConsumer() {
        contextRunner
                .withPropertyValues("stats.writer.stat-type=lorastats")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OmsstatsConsumer.class);
                    assertThat(context).hasSingleBean(LorastatsConsumer.class);
                });
    }

    @Test
    void aSysstatsDeploymentDoesNotCreateTheOmsstatsConsumer() {
        contextRunner
                .withPropertyValues("stats.writer.stat-type=sysstats")
                .withBean(StatsMessageProcessor.class, () -> TestProcessors.noOp(StatType.SYSSTATS))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OmsstatsConsumer.class);
                    assertThat(context).hasSingleBean(SysstatsConsumer.class);
                });
    }

    @Test
    void theDevelopmentAllTypesModeCreatesAllThreeConsumersAndQueueSets() {
        contextRunner
                .withPropertyValues("stats.writer.stat-type=all")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OmsstatsConsumer.class);
                    assertThat(context).hasSingleBean(LorastatsConsumer.class);
                    assertThat(context).hasSingleBean(SysstatsConsumer.class);
                    // One shared exchange pair, three per-type queue sets, no clash.
                    assertThat(context).hasBean("statsInputExchange");
                    assertThat(context).hasBean("statsDeadLetterExchange");
                    assertThat(context).hasBean("omsstatsMainQueue");
                    assertThat(context).hasBean("omsstatsRetryTier3Queue");
                    assertThat(context).hasBean("omsstatsDeadLetterQueue");
                    assertThat(context).hasBean("lorastatsMainQueue");
                    assertThat(context).hasBean("sysstatsMainQueue");
                });
    }

    /** The omsstats consumer must never start against a no-op: it would ACK and discard. */
    @Test
    void doesNotStartBeforeItsOwnProcessorExists() {
        contextRunner
                .withPropertyValues("stats.writer.stat-type=omsstats")
                .withBean(StatsMessageProcessor.class, () -> TestProcessors.noOp(StatType.LORASTATS))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OmsstatsConsumer.class);
                    StatsMessageProcessorAvailability availability =
                            context.getBean(StatsMessageProcessorAvailability.class);
                    assertThat(availability.available(StatType.OMSSTATS.wireName())).isFalse();
                    assertThat(availability.available(StatType.LORASTATS.wireName())).isTrue();
                });
    }
}
