package com.zenner.datahub.stats.input.publish;

import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.amqp.AmqpTopology.ExchangeDefinition;
import com.zenner.datahub.stats.input.ingest.IngestMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What Input declares on the broker, and the callback that makes an unroutable
 * message visible (T-014, T-015; ADR-002 §9 and §11).
 *
 * <p><strong>Input declares the exchange and nothing else (DL-118).</strong> ADR-002
 * §11 puts topology declaration in the broker-connected modules and requires the
 * declarations to come from {@link AmqpTopology} and be identical and idempotent. It
 * does not say every module declares every object, and here that distinction carries
 * weight in two directions:</p>
 *
 * <ul>
 *   <li>Queues belong to the module that drains them. If Input declared all five main
 *       queues, a deployment with no Writer for a type would accumulate an undrained
 *       quorum queue that nothing alerts on, because depth alerting is the Writer's
 *       (ADR-002 §12).</li>
 *   <li>It would also make the missing-binding case unreachable — and that case is the
 *       entire reason {@code mandatory} is set. A publisher confirm says the broker
 *       accepted the message, not that a queue wanted it; declaring the queues here
 *       would hide a half-applied topology change behind Input's own declaration
 *       rather than surfacing it as a return.</li>
 * </ul>
 *
 * <p>The exchange definition must stay argument-identical to the Writer's or the
 * second module to start dies with {@code RESOURCE_LOCKED}. {@code AmqpTopology}
 * deliberately holds no Spring AMQP objects — {@code stats-common} has no broker
 * dependency — so the pair is hand-built in two places and T-032 is the cross-module
 * enforcement, and since T-032 it is {@code AmqpDeclarationAssert} — one assertion
 * implementation called by both modules' tests.</p>
 */
@Configuration(proxyBeanMethods = false)
public class InputAmqpConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InputAmqpConfiguration.class);

    /**
     * Built from {@link AmqpTopology#inputExchange()}, not from remembered flags
     * (T-032). Durability and auto-delete are as fatal to a second declaring module as
     * the arguments are, and they used to live only in this constructor call and the
     * Writer's.
     */
    @Bean
    public TopicExchange statsInputExchange() {
        ExchangeDefinition definition = AmqpTopology.inputExchange();
        return new TopicExchange(
                definition.name(), definition.durable(), definition.autoDelete());
    }

    /**
     * ADR-002 §9 requires a {@code ReturnsCallback} alongside {@code mandatory=true},
     * and this is it. The <em>decision</em> to answer 503 is not taken here: a callback
     * fires on the broker's connection thread and has no way to reach the HTTP request
     * that published the message. {@link ConfirmingStatsPublisher} reads the return off
     * the {@code CorrelationData} instead, which is the only linkage the framework
     * offers. What the callback owns is the ERROR log the ADR asks for — the routing
     * key that nothing was bound to is the one fact an operator needs, and it is not on
     * the returned body.
     */
    @Bean
    public RabbitTemplateCustomizer statsReturnsCallbackCustomizer() {
        return template -> template.setReturnsCallback(returned ->
                log.error(
                        "Message returned as UNROUTABLE: exchange={} routingKey={} replyCode={} "
                                + "replyText={}. The broker accepted the publish and no queue was "
                                + "bound to take it — a queue is missing or the topology is "
                                + "half-applied. The request is answered 503 so Heimdal resends "
                                + "(ADR-002 §9, {} counter)",
                        returned.getExchange(),
                        returned.getRoutingKey(),
                        returned.getReplyCode(),
                        returned.getReplyText(),
                        IngestMetrics.UNROUTABLE));
    }

    /**
     * The confirming publisher. Registering it here is what retires
     * {@link UnconfirmedStatsPublisher}, whose {@code @ConditionalOnMissingBean}
     * registration drops out on its own — {@code PublisherConfiguration} is untouched.
     */
    @Bean
    public StatsPublisher confirmingStatsPublisher(
            RabbitTemplate rabbitTemplate,
            AmqpPublisherProperties properties,
            IngestMetrics metrics) {
        return new ConfirmingStatsPublisher(rabbitTemplate, properties, metrics);
    }
}
