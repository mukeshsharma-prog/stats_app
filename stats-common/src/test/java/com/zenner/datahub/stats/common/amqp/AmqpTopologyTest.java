package com.zenner.datahub.stats.common.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zenner.datahub.stats.common.model.StatType;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class AmqpTopologyTest {

    @Test
    void exposesTheContractedSharedNamesAndArgumentKeys() {
        assertThat(AmqpTopology.INPUT_EXCHANGE).isEqualTo("app_stats_input");
        assertThat(AmqpTopology.DEAD_LETTER_EXCHANGE).isEqualTo("app_stats_dlx");
        assertThat(AmqpTopology.RETRY_TIERS).isEqualTo(3);
        assertThat(AmqpTopology.X_QUEUE_TYPE).isEqualTo("x-queue-type");
        assertThat(AmqpTopology.QUORUM).isEqualTo("quorum");
        assertThat(AmqpTopology.X_DEAD_LETTER_EXCHANGE)
                .isEqualTo("x-dead-letter-exchange");
        assertThat(AmqpTopology.X_MESSAGE_TTL).isEqualTo("x-message-ttl");
    }

    @ParameterizedTest
    @EnumSource(StatType.class)
    void derivesEveryResourceNameFromTheStatTypeWireName(StatType type) {
        String base = "app_statsstore." + type.wireName();

        assertThat(AmqpTopology.routingKey(type)).isEqualTo(type.wireName());
        assertThat(AmqpTopology.mainQueue(type)).isEqualTo(base);
        assertThat(AmqpTopology.retryQueue(type, 1)).isEqualTo(base + ".retry.1");
        assertThat(AmqpTopology.retryQueue(type, 2)).isEqualTo(base + ".retry.2");
        assertThat(AmqpTopology.retryQueue(type, 3)).isEqualTo(base + ".retry.3");
        assertThat(AmqpTopology.deadLetterQueue(type)).isEqualTo(base + ".dlq");
    }

    @Test
    void generatesTwentyFiveUniqueQueueNames() {
        Set<String> names = allQueueNames();

        assertThat(names).hasSize(25);
    }

    @Test
    void generatesOnlyLowercaseQueueNames() {
        allQueueNames().forEach(name ->
                assertThat(name).isEqualTo(name.toLowerCase(Locale.ROOT)));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 4, 100})
    void rejectsRetryTiersOutsideTheContractedRange(int tier) {
        assertThatThrownBy(() -> AmqpTopology.retryQueue(StatType.LORASTATS, tier))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Retry tier must be between 1 and 3");
    }

    @Test
    void rejectsNullStatTypes() {
        assertThatThrownBy(() -> AmqpTopology.routingKey(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Stat type must not be null");
        assertThatThrownBy(() -> AmqpTopology.mainQueue(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Stat type must not be null");
        assertThatThrownBy(() -> AmqpTopology.retryQueue(null, 1))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Stat type must not be null");
        assertThatThrownBy(() -> AmqpTopology.deadLetterQueue(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Stat type must not be null");
    }

    private static Set<String> allQueueNames() {
        Set<String> names = new LinkedHashSet<>();
        for (StatType type : StatType.values()) {
            names.add(AmqpTopology.mainQueue(type));
            IntStream.rangeClosed(1, AmqpTopology.RETRY_TIERS)
                    .mapToObj(tier -> AmqpTopology.retryQueue(type, tier))
                    .forEach(names::add);
            names.add(AmqpTopology.deadLetterQueue(type));
        }
        return names;
    }
}
