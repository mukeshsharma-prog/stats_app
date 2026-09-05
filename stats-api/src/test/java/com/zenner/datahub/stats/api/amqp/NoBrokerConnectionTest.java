package com.zenner.datahub.stats.api.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The third part of T-032: {@code stats-api} has no broker connection at all, and the
 * absence is the assertion (Q3a, ADR-002 §11).
 *
 * <p>ZC's answer to Q3 read <em>"every module have to declare the exchanges, queues and
 * bindings."</em> Taken literally that hands a read-only HTTP module a RabbitMQ
 * dependency it has no use for, and adds a third declaring module to a set where any
 * argument mismatch is a {@code RESOURCE_LOCKED} startup failure rather than a warning.
 * ADR-002 §11 reads it as <em>every broker-connected module</em>.</p>
 *
 * <p><strong>Why a classpath assertion and not a bean assertion.</strong> "No
 * {@code ConnectionFactory} bean" can only be written by a test that can name the type,
 * which means putting spring-amqp on this module's test classpath — and the moment it
 * is there, someone can move it to {@code implementation} and this test still passes.
 * Asserting the class cannot be loaded is the version that fails the day the dependency
 * appears.</p>
 */
class NoBrokerConnectionTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "org.springframework.amqp.rabbit.connection.ConnectionFactory",
                "org.springframework.amqp.rabbit.core.RabbitTemplate",
                "com.rabbitmq.client.Channel"
            })
    @DisplayName("no AMQP client type is on this module's classpath, in any scope")
    void brokerTypesAreNotOnTheClasspath(String type) {
        assertThatExceptionOfType(ClassNotFoundException.class)
                .isThrownBy(() -> Class.forName(type, false, getClass().getClassLoader()));
    }

    @Test
    @DisplayName("the shared topology constants are reachable, and still unused here")
    void topologyConstantsRemainAvailableWithoutABroker() {
        // stats-common is a dependency and it carries AmqpTopology, so "no broker" has to
        // mean no CLIENT rather than no constants. If this module ever needs a queue name
        // for a diagnostic, that is fine; opening a connection is not.
        assertThat(com.zenner.datahub.stats.common.amqp.AmqpTopology.INPUT_EXCHANGE)
                .isEqualTo("app_stats_input");
    }
}
