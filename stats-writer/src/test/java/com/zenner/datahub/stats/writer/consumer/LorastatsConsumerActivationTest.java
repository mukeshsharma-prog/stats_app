package com.zenner.datahub.stats.writer.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.writer.retry.FailedDeliveryRouter;
import com.zenner.datahub.stats.writer.retry.WriterAmqpProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LorastatsConsumerActivationTest {

    private static final String LORASTATS = StatType.LORASTATS.wireName();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(LorastatsConsumerConfiguration.class)
            .withBean(io.micrometer.core.instrument.MeterRegistry.class,
                    io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
            .withBean(
                    SimpleRabbitListenerContainerFactoryConfigurer.class,
                    () -> mock(SimpleRabbitListenerContainerFactoryConfigurer.class))
            .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
            // The configuration declares the whole failure arm, so the tier
            // delays it reads and the router the consumer takes both have to be present.
            // Supplied directly rather than bound from properties: what this class tests
            // is ACTIVATION, and the delays are covered by WriterAmqpPropertiesTest and
            // LorastatsTopologyDeclarationTest.
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
                    "stats.consumer.lorastats.prefetch=500");

    @Test
    void doesNotStartANoOpConsumerBeforeTheProcessingPipelineExists() {
        contextRunner.withPropertyValues("stats.writer.stat-type=lorastats").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LorastatsConsumer.class);
            assertThat(
                            context.getBean(StatsMessageProcessorAvailability.class)
                                    .available(LORASTATS))
                    .isFalse();
        });
    }

    @Test
    void createsTheConsumerWhenLorastatsAndARealProcessorAreConfigured() {
        contextRunner
                .withPropertyValues("stats.writer.stat-type=lorastats")
                .withBean(
                        StatsMessageProcessor.class,
                        () -> TestProcessors.noOp(StatType.LORASTATS))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LorastatsConsumer.class);
                    assertThat(
                                    context.getBean(StatsMessageProcessorAvailability.class)
                                            .available(LORASTATS))
                            .isTrue();
                });
    }

    @Test
    void anotherProductionStatTypeCannotActivateTheLorastatsConsumer() {
        contextRunner
                .withPropertyValues("stats.writer.stat-type=ltestats")
                .withBean(
                        StatsMessageProcessor.class,
                        () -> TestProcessors.noOp(StatType.LTESTATS))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(LorastatsConsumer.class);
                });
    }

    /**
     * Rule 6: adding a stat type must not change an existing one. Phase 3 registers
     * five processors, and resolving "the" processor by uniqueness would start
     * throwing {@code NoUniqueBeanDefinitionException} on the second one — breaking
     * lorastats by adding sysstats. Selection is by stat type, so a foreign
     * processor in the context is simply not a match.
     */
    @Test
    void aSecondStatTypesProcessorNeitherActivatesNorBreaksLorastats() {
        contextRunner
                .withPropertyValues("stats.writer.stat-type=lorastats")
                .withBean(
                        "sysstatsProcessor",
                        StatsMessageProcessor.class,
                        () -> TestProcessors.noOp(StatType.SYSSTATS))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    StatsMessageProcessorAvailability availability =
                            context.getBean(StatsMessageProcessorAvailability.class);
                    assertThat(availability.available(LORASTATS)).isFalse();
                    assertThat(availability.available(StatType.SYSSTATS.wireName())).isTrue();
                });
    }

    @Test
    void selectsTheLorastatsProcessorWhenSeveralStatTypesAreRegistered() {
        StatsMessageProcessor lorastats = TestProcessors.noOp(StatType.LORASTATS);
        contextRunner
                .withPropertyValues("stats.writer.stat-type=lorastats")
                .withBean("lorastatsProcessor", StatsMessageProcessor.class, () -> lorastats)
                .withBean(
                        "netstatsProcessor",
                        StatsMessageProcessor.class,
                        () -> TestProcessors.noOp(StatType.NETSTATS))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    StatsMessageProcessorAvailability availability =
                            context.getBean(StatsMessageProcessorAvailability.class);
                    assertThat(availability.available(LORASTATS)).isTrue();
                    assertThat(availability.requiredProcessor(StatType.LORASTATS))
                            .isSameAs(lorastats);
                });
    }

    /** Two processors claiming one type is a misconfiguration, not a coin toss. */
    @Test
    void refusesToChooseBetweenTwoProcessorsForTheSameStatType() {
        contextRunner
                .withPropertyValues("stats.writer.stat-type=lorastats")
                .withBean("first", StatsMessageProcessor.class,
                        () -> TestProcessors.noOp(StatType.LORASTATS))
                .withBean("second", StatsMessageProcessor.class,
                        () -> TestProcessors.noOp(StatType.LORASTATS))
                .run(context -> {
                    StatsMessageProcessorAvailability availability =
                            context.getBean(StatsMessageProcessorAvailability.class);
                    assertThatIllegalStateException()
                            .isThrownBy(() -> availability.requiredProcessor(StatType.LORASTATS))
                            .withMessageContaining("More than one");
                });
    }

    @Test
    void reportsAMissingProcessorRatherThanReturningNull() {
        contextRunner.withPropertyValues("stats.writer.stat-type=lorastats").run(context -> {
            StatsMessageProcessorAvailability availability =
                    context.getBean(StatsMessageProcessorAvailability.class);
            assertThatIllegalStateException()
                    .isThrownBy(() -> availability.requiredProcessor(StatType.LORASTATS))
                    .withMessageContaining("No StatsMessageProcessor registered");
        });
    }
}
