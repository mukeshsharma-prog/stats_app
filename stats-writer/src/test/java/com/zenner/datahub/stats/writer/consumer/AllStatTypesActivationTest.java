package com.zenner.datahub.stats.writer.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.writer.metrics.QueueAgeMetrics;
import com.zenner.datahub.stats.writer.retry.FailedDeliveryRouter;
import com.zenner.datahub.stats.writer.retry.WriterAmqpProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AllStatTypesActivationTest {
    private static final Map<StatType, Class<?>> CONSUMERS = Map.of(
            StatType.LORASTATS, LorastatsConsumer.class, StatType.SYSSTATS, SysstatsConsumer.class,
            StatType.OMSSTATS, OmsstatsConsumer.class, StatType.LTESTATS, LtestatsConsumer.class,
            StatType.NETSTATS, NetstatsConsumer.class);

    private ApplicationContextRunner context() {
        var runner = new ApplicationContextRunner().withUserConfiguration(WriterAmqpConfiguration.class,
                LorastatsConsumerConfiguration.class, SysstatsConsumerConfiguration.class,
                OmsstatsConsumerConfiguration.class, LtestatsConsumerConfiguration.class, NetstatsConsumerConfiguration.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(QueueAgeMetrics.class, () -> mock(QueueAgeMetrics.class))
                .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
                .withBean(SimpleRabbitListenerContainerFactoryConfigurer.class,
                        () -> mock(SimpleRabbitListenerContainerFactoryConfigurer.class))
                .withBean(FailedDeliveryRouter.class, () -> mock(FailedDeliveryRouter.class))
                .withBean(WriterAmqpProperties.class, () -> new WriterAmqpProperties(
                        new WriterAmqpProperties.Retry(Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofMinutes(30)),
                        3, Duration.ofSeconds(5), Duration.ofSeconds(5)));
        for (StatType type : StatType.values()) {
            String prefix = "stats.consumer." + type.wireName();
            runner = runner.withPropertyValues(prefix + ".concurrency=1", prefix + ".prefetch=500", prefix + ".shutdown-timeout=5s");
        }
        return runner;
    }

    @ParameterizedTest
    @EnumSource(StatType.class)
    void onlyTheSelectedConsumerActivatesAndAllTopologyCoexists(StatType selected) {
        context().withPropertyValues("stats.writer.stat-type=" + selected.wireName()).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            CONSUMERS.forEach((type, consumer) -> {
                assertThat(ctx.getBeansOfType(consumer)).hasSize(type == selected ? 1 : 0);
                assertThat(ctx).hasBean(type.wireName() + "MainQueue").hasBean(type.wireName() + "RetryTier3Queue")
                        .hasBean(type.wireName() + "DeadLetterQueue");
            });
        });
    }

    @Test
    void localAllModeCreatesFiveConsumersWithoutBeanAmbiguity() {
        context().withPropertyValues("stats.writer.stat-type=all").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            CONSUMERS.values().forEach(consumer -> assertThat(ctx.getBeansOfType(consumer)).hasSize(1));
        });
    }
}
