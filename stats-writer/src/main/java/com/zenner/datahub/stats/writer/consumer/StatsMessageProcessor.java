package com.zenner.datahub.stats.writer.consumer;

import com.zenner.datahub.stats.common.model.StatType;
import org.springframework.amqp.core.Message;

/**
 * Completes the durable handling of one delivery before the consumer may ACK it.
 *
 * <p>A successful return means a later processing stage has reached a safe hand-off
 * point: normally the PostgreSQL transaction has committed, or a retry/DLQ publish
 * has been confirmed. Throwing leaves the original delivery unacknowledged. This
 * contract keeps ACK ordering in one place, beside the confirmed retry/DLQ
 * path.</p>
 *
 * <p>{@link #statType()} exists so that processors are selected by the type they
 * serve rather than by being the only bean of this interface. Adding a
 * stat type must not change an existing one, and this fans out to five
 * implementations — resolving "the" processor from the context would start throwing
 * {@code NoUniqueBeanDefinitionException} on the second one, breaking lorastats by
 * adding sysstats.</p>
 */
public interface StatsMessageProcessor {

    /** The one stat type this processor handles. Never more than one. */
    StatType statType();

    void process(StatType expectedType, Message message) throws Exception;
}
