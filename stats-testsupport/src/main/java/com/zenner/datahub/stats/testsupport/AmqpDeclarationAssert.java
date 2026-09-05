package com.zenner.datahub.stats.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.common.amqp.AmqpTopology.ExchangeDefinition;
import com.zenner.datahub.stats.common.amqp.AmqpTopology.QueueDefinition;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;

/**
 * T-032 — the declaration-conflict enforcement, in one place both broker-connected
 * modules call.
 *
 * <p><strong>The failure it exists to stop.</strong> {@code stats-input} and
 * {@code stats-writer} both declare topology (ADR-002 §11). RabbitMQ does not merge
 * two declarations of the same resource: it rejects the second with
 * {@code RESOURCE_LOCKED} and that module fails to start. Quorum makes it worse — a
 * queue's type is fixed at declaration, so correcting one means deleting and
 * redeclaring it (ADR-002 §8). Every field matters: name, durability, auto-delete,
 * exclusivity and every argument.</p>
 *
 * <p><strong>Why an assertion rather than a shared factory.</strong> A factory
 * returning Spring AMQP objects would have to live in {@code stats-common}, which
 * deliberately holds no broker client (D15), or in one application module, which the
 * other may not depend on. {@code AmqpTopology} therefore owns the <em>values</em> as
 * plain records and each module owns the four lines that turn one into a Spring
 * object. This class checks those four lines, in both modules, from one
 * implementation — so a drift in either is a build failure rather than a startup
 * failure in whichever environment happens to boot them in the wrong order.</p>
 */
public final class AmqpDeclarationAssert {

    private AmqpDeclarationAssert() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** Asserts a declared exchange matches the shared definition in every field. */
    public static void assertMatches(Exchange exchange, ExchangeDefinition definition) {
        assertThat(exchange.getName()).isEqualTo(definition.name());
        assertThat(exchange.getType()).isEqualTo(definition.type());
        assertThat(exchange.isDurable()).isEqualTo(definition.durable());
        assertThat(exchange.isAutoDelete()).isEqualTo(definition.autoDelete());
        // An argument nobody intended is still a mismatch to the broker.
        assertThat(exchange.getArguments()).isEmpty();
    }

    /** Asserts a declared queue matches the shared definition in every field. */
    public static void assertMatches(Queue queue, QueueDefinition definition) {
        assertThat(queue.getName()).isEqualTo(definition.name());
        assertThat(queue.isDurable()).isEqualTo(definition.durable());
        assertThat(queue.isExclusive()).isEqualTo(definition.exclusive());
        assertThat(queue.isAutoDelete()).isEqualTo(definition.autoDelete());
        assertThat(queue.getArguments()).containsExactlyInAnyOrderEntriesOf(definition.arguments());
    }
}
