package com.zenner.datahub.stats.common.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zenner.datahub.stats.common.model.StatType;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
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
        assertThat(AmqpTopology.X_DEAD_LETTER_ROUTING_KEY)
                .isEqualTo("x-dead-letter-routing-key");
    }

    // === Declaration arguments =============================================
    // Two modules declaring the same queue with different arguments is a
    // RESOURCE_LOCKED startup failure, not a warning, and a quorum queue's type is
    // fixed at declaration. These assertions pin the maps so the cross-module test
    // (AmqpDeclarationAssert) compares one source rather than two hand-built ones.

    @Test
    void declaresMainQueuesAsQuorumAndNothingElse() {
        assertThat(AmqpTopology.mainQueueArguments())
                .containsExactly(entry(AmqpTopology.X_QUEUE_TYPE, AmqpTopology.QUORUM));
    }

    @ParameterizedTest
    @EnumSource(StatType.class)
    void declaresRetryTiersWithTheDelayAsTtlAndABounceBackToTheType(StatType type) {
        Map<String, Object> arguments =
                AmqpTopology.retryQueueArguments(type, 1, Duration.ofSeconds(30));

        assertThat(arguments).containsOnly(
                entry(AmqpTopology.X_QUEUE_TYPE, AmqpTopology.QUORUM),
                entry(AmqpTopology.X_MESSAGE_TTL, 30_000L),
                entry(AmqpTopology.X_DEAD_LETTER_EXCHANGE, AmqpTopology.DEAD_LETTER_EXCHANGE),
                entry(AmqpTopology.X_DEAD_LETTER_ROUTING_KEY, type.wireName()));
    }

    @Test
    void rejectsARetryDelayThatWouldMakeTheTierAnImmediateRequeue() {
        assertThatThrownBy(() -> AmqpTopology.retryQueueArguments(
                        StatType.LORASTATS, 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> AmqpTopology.retryQueueArguments(
                        StatType.LORASTATS, 1, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }

    /**
     * Trap 17. A TTL on the dead-letter queue looks like disk hygiene and is silent
     * data loss: the message has already survived three retry tiers and is the only
     * remaining copy of a record Heimdal believes it delivered. A dead-letter exchange
     * on a DLQ is the same failure wearing a different name — the last copy leaves and
     * nothing says so. Asserting the map exactly is what stops either being added
     * later "to bound broker disk".
     */
    @Test
    void declaresDeadLetterQueuesWithNoExpiryAndNoOnwardRoute() {
        Map<String, Object> arguments = AmqpTopology.deadLetterQueueArguments();

        assertThat(arguments)
                .containsExactly(entry(AmqpTopology.X_QUEUE_TYPE, AmqpTopology.QUORUM))
                .doesNotContainKey(AmqpTopology.X_MESSAGE_TTL)
                .doesNotContainKey(AmqpTopology.X_DEAD_LETTER_EXCHANGE)
                .doesNotContainKey(AmqpTopology.X_DEAD_LETTER_ROUTING_KEY);
    }

    @Test
    void declaresEveryQueueKindAsQuorum() {
        assertThat(AmqpTopology.mainQueueArguments())
                .containsEntry(AmqpTopology.X_QUEUE_TYPE, AmqpTopology.QUORUM);
        assertThat(AmqpTopology.retryQueueArguments(
                        StatType.LORASTATS, 3, Duration.ofMinutes(30)))
                .containsEntry(AmqpTopology.X_QUEUE_TYPE, AmqpTopology.QUORUM);
        assertThat(AmqpTopology.deadLetterQueueArguments())
                .containsEntry(AmqpTopology.X_QUEUE_TYPE, AmqpTopology.QUORUM);
    }

    @Test
    void returnsArgumentMapsThatCannotBeMutatedByACaller() {
        assertThatThrownBy(() -> AmqpTopology.mainQueueArguments().put("x-max-length", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> AmqpTopology.deadLetterQueueArguments()
                        .put(AmqpTopology.X_MESSAGE_TTL, 1L))
                .isInstanceOf(UnsupportedOperationException.class);
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
        assertThatThrownBy(() -> AmqpTopology.retryQueueArguments(
                        null, 1, Duration.ofSeconds(30)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Stat type must not be null");
        assertThatThrownBy(() -> AmqpTopology.retryQueueArguments(
                        StatType.LORASTATS, 1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Retry tier delay must not be null");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 4, 100})
    void rejectsRetryTiersOutsideTheContractedRangeWhenBuildingArguments(int tier) {
        assertThatThrownBy(() -> AmqpTopology.retryQueueArguments(
                        StatType.LORASTATS, tier, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Retry tier must be between 1 and 3");
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

    @ParameterizedTest
    @EnumSource(StatType.class)
    void separatesTheRetryBounceBackFromTheDeadLetterRoute(StatType type) {
        // Both journeys cross app_stats_dlx. One returns a record to the main queue for
        // another attempt; the other retires it. If they shared a routing key every
        // dead-letter would land back on the main queue and retry forever — and the
        // topology would still declare without complaint.
        assertThat(AmqpTopology.deadLetterRoutingKey(type))
                .isNotEqualTo(AmqpTopology.routingKey(type))
                .isEqualTo(type.wireName() + ".dlq");
    }

    @Test
    void definesTheInputExchangeAsDurableTopicWithNoAutoDelete() {
        // Durability and auto-delete are as fatal to a second declaring module as the
        // arguments are. An auto-delete exchange also disappears with its last
        // binding, and the next publish is then unroutable rather than queued.
        AmqpTopology.ExchangeDefinition definition = AmqpTopology.inputExchange();

        assertThat(definition.name()).isEqualTo(AmqpTopology.INPUT_EXCHANGE);
        assertThat(definition.type()).isEqualTo(AmqpTopology.ExchangeDefinition.TOPIC);
        assertThat(definition.durable()).isTrue();
        assertThat(definition.autoDelete()).isFalse();
    }

    @Test
    void definesTheDeadLetterExchangeIdenticallyToTheIngressExchangeApartFromItsName() {
        AmqpTopology.ExchangeDefinition dlx = AmqpTopology.deadLetterExchange();

        assertThat(dlx.name()).isEqualTo(AmqpTopology.DEAD_LETTER_EXCHANGE);
        assertThat(dlx.type()).isEqualTo(AmqpTopology.ExchangeDefinition.TOPIC);
        assertThat(dlx.durable()).isTrue();
        assertThat(dlx.autoDelete()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(StatType.class)
    void definesEveryQueueAsDurableNonExclusiveAndNotAutoDelete(StatType type) {
        // Exclusive or auto-delete would tie the queue to one consumer's connection, so
        // a Writer restart would drop every buffered record on the floor.
        List<AmqpTopology.QueueDefinition> definitions =
                List.of(
                        AmqpTopology.mainQueueDefinition(type),
                        AmqpTopology.retryQueueDefinition(type, 1, Duration.ofSeconds(30)),
                        AmqpTopology.deadLetterQueueDefinition(type));

        assertThat(definitions)
                .allSatisfy(
                        definition -> {
                            assertThat(definition.durable()).isTrue();
                            assertThat(definition.exclusive()).isFalse();
                            assertThat(definition.autoDelete()).isFalse();
                        });
    }

    @ParameterizedTest
    @EnumSource(StatType.class)
    void definitionsCarryTheSameNamesAndArgumentsAsTheNameAccessors(StatType type) {
        // The definitions must not become a second source of truth for names.
        assertThat(AmqpTopology.mainQueueDefinition(type).name())
                .isEqualTo(AmqpTopology.mainQueue(type));
        assertThat(AmqpTopology.deadLetterQueueDefinition(type).name())
                .isEqualTo(AmqpTopology.deadLetterQueue(type));
        assertThat(AmqpTopology.deadLetterQueueDefinition(type).arguments())
                .isEqualTo(AmqpTopology.deadLetterQueueArguments());
        assertThat(AmqpTopology.retryQueueDefinition(type, 2, Duration.ofMinutes(5)).arguments())
                .isEqualTo(AmqpTopology.retryQueueArguments(type, 2, Duration.ofMinutes(5)));
    }
}
