package com.zenner.datahub.stats.input.log;

import com.fasterxml.jackson.databind.JsonNode;
import com.zenner.datahub.stats.common.error.ErrorCode;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.input.config.IngestSource;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/** Scoped structured-log context for one ingest request (T-005, ADR-001). */
public final class IngestLogContext {

    public static final String TRACE_ID = "traceId";
    public static final String UID = "uid";
    public static final String STAT_TYPE = "statType";
    public static final String TENANT = "tenant";
    public static final String CLSBOX = "clsbox";
    public static final String ERROR_CODE = "errorCode";
    /** DL-151: which ingest door the request came through — {@code internal} or {@code external}. */
    public static final String INGEST_SOURCE = "ingestSource";

    public static final String UNKNOWN = "unknown";
    public static final String NO_ERROR = "none";

    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private IngestLogContext() {}

    /**
     * Starts a fresh request context and restores the caller's context on close.
     * Unknown placeholders keep pre-parse failures structurally searchable without
     * copying unvalidated attacker-controlled values into logs.
     */
    public static Scope openRequest() {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        boolean previousActive = Boolean.TRUE.equals(ACTIVE.get());
        MDC.clear();
        ACTIVE.set(true);
        MDC.put(TRACE_ID, UUID.randomUUID().toString());
        MDC.put(UID, UNKNOWN);
        MDC.put(STAT_TYPE, UNKNOWN);
        MDC.put(TENANT, UNKNOWN);
        MDC.put(CLSBOX, UNKNOWN);
        MDC.put(ERROR_CODE, NO_ERROR);
        MDC.put(INGEST_SOURCE, UNKNOWN);
        return new Scope(previous, previousActive);
    }

    /** Adds identifiers only after the envelope schema has validated them. */
    public static void bindValidatedRecord(JsonNode record, StatType statType) {
        if (!active()) {
            return;
        }
        MDC.put(UID, record.path(UID).textValue());
        MDC.put(STAT_TYPE, statType.wireName());
        MDC.put(TENANT, record.path(TENANT).textValue());
        MDC.put(CLSBOX, record.path(CLSBOX).textValue());
    }

    /** Binds the enum-backed type even when another envelope member is invalid. */
    public static void statType(StatType statType) {
        if (active() && statType != null) {
            MDC.put(STAT_TYPE, statType.wireName());
        }
    }

    /** Binds the ingest door once the logging filter has matched the path (DL-151). */
    public static void source(IngestSource source) {
        if (active() && source != null) {
            MDC.put(INGEST_SOURCE, source.label());
        }
    }

    public static void errorCode(ErrorCode errorCode) {
        if (active()) {
            MDC.put(ERROR_CODE, errorCode.wireCode());
        }
    }

    public static String traceId() {
        return active() ? valueOrUnknown(TRACE_ID) : UNKNOWN;
    }

    public static String uid() {
        return active() ? valueOrUnknown(UID) : UNKNOWN;
    }

    private static String valueOrUnknown(String key) {
        String value = MDC.get(key);
        return value == null || value.isBlank() ? UNKNOWN : value;
    }

    private static boolean active() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    /** Restores rather than blindly clears context owned by an outer framework. */
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
