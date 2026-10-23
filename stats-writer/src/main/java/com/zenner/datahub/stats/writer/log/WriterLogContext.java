package com.zenner.datahub.stats.writer.log;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.common.model.StatsEnvelope;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/** Scoped structured-log context for one Writer delivery. */
public final class WriterLogContext {

    public static final String TRACE_ID = "traceId";
    public static final String UID = "uid";
    public static final String STAT_TYPE = "statType";
    public static final String TENANT = "tenant";
    public static final String CLSBOX = "clsbox";
    public static final String ERROR_CODE = "errorCode";

    public static final String UNKNOWN = "unknown";
    public static final String NO_ERROR = "none";

    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private WriterLogContext() {}

    /** Opens context before parsing, so malformed deliveries remain traceable. */
    public static Scope open(Message message, StatType statType) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(statType, "statType");
        Map<String, String> previous = MDC.getCopyOfContextMap();
        boolean previousActive = Boolean.TRUE.equals(ACTIVE.get());
        MessageProperties properties = message.getMessageProperties();

        MDC.clear();
        ACTIVE.set(true);
        MDC.put(TRACE_ID, valueOr(properties.getCorrelationId(), UUID.randomUUID().toString()));
        MDC.put(UID, valueOr(properties.getMessageId(), UNKNOWN));
        MDC.put(STAT_TYPE, statType.wireName());
        MDC.put(TENANT, UNKNOWN);
        MDC.put(CLSBOX, UNKNOWN);
        MDC.put(ERROR_CODE, NO_ERROR);
        return new Scope(previous, previousActive);
    }

    /** Replaces placeholders only after the body has been safely deserialized. */
    public static void bindRecord(StatsEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (!active()) {
            return;
        }
        MDC.put(UID, valueOr(envelope.uid(), UNKNOWN));
        MDC.put(STAT_TYPE, envelope.type() == null ? UNKNOWN : envelope.type().wireName());
        MDC.put(TENANT, valueOr(envelope.tenant(), UNKNOWN));
        MDC.put(CLSBOX, valueOr(envelope.clsbox(), UNKNOWN));
    }

    public static void errorCode(String errorCode) {
        if (active()) {
            MDC.put(ERROR_CODE, valueOr(errorCode, UNKNOWN));
        }
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean active() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    public static final class Scope implements AutoCloseable {

        private final Map<String, String> previous;
        private final boolean previousActive;
        private boolean closed;

        private Scope(Map<String, String> previous, boolean previousActive) {
            this.previous = previous;
            this.previousActive = previousActive;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            MDC.clear();
            if (previous != null) {
                MDC.setContextMap(previous);
            }
            if (previousActive) {
                ACTIVE.set(true);
            } else {
                ACTIVE.remove();
            }
        }
    }
}
