package com.zenner.datahub.stats.writer.consumer;

import com.zenner.datahub.stats.common.model.StatType;
import org.springframework.amqp.core.Message;

/**
 * Completes the durable handling of one delivery before the consumer may ACK it.
 *
 * <p>A successful return means a later processing stage has reached a safe hand-off
 * point: normally the PostgreSQL transaction has committed, or a retry/DLQ publish
 * has been confirmed. Throwing leaves the original delivery unacknowledged. This
 * contract keeps ACK ordering in one place while T-021/T-022 add persistence and
 * T-024 adds the confirmed retry/DLQ path (ADR-005 D7).</p>
 */
@FunctionalInterface
public interface StatsMessageProcessor {

    void process(StatType expectedType, Message message) throws Exception;
}
