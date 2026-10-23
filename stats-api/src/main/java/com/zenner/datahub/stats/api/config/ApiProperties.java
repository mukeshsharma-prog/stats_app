package com.zenner.datahub.stats.api.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The read API's paging, window and cursor settings (openapi.yaml).
 *
 * <p><strong>No value has a default in code.</strong> Every one is set in
 * {@code application.properties}, and an unset one stops the context. That is the same
 * rule the security properties follow, applied here because two of these are
 * controls rather than tuning: the cursor signing key is what makes a cursor unforgeable,
 * and the query window is what stops a tenant-wide scan over 115.2M rows a day.</p>
 *
 * @param defaultPageSize the {@code limit} applied when the caller sends none (contract: 100)
 * @param maxPageSize the largest {@code limit} accepted (contract: 1000, and never more)
 * @param maxQueryWindow the widest {@code to - from} on the tenant-wide list only —
 *     {@code PROVISIONAL} in the contract; the device-scoped paths have no window
 * @param cursor how continuation tokens are signed and how long they live
 */
@ConfigurationProperties(prefix = "stats.api")
public record ApiProperties(
        Integer defaultPageSize, Integer maxPageSize, Duration maxQueryWindow, Cursor cursor) {

    /** The contract's ceiling on {@code limit}; configuration may lower it, never raise it. */
    public static final int CONTRACT_MAX_PAGE_SIZE = 1000;

    public ApiProperties {
        require(defaultPageSize, "stats.api.default-page-size");
        require(maxPageSize, "stats.api.max-page-size");
        require(maxQueryWindow, "stats.api.max-query-window");
        require(cursor, "stats.api.cursor.*");
        if (maxPageSize < 1 || maxPageSize > CONTRACT_MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "stats.api.max-page-size must be between 1 and " + CONTRACT_MAX_PAGE_SIZE);
        }
        if (defaultPageSize < 1 || defaultPageSize > maxPageSize) {
            throw new IllegalArgumentException(
                    "stats.api.default-page-size must be between 1 and stats.api.max-page-size");
        }
        if (maxQueryWindow.isNegative() || maxQueryWindow.isZero()) {
            throw new IllegalArgumentException("stats.api.max-query-window must be positive");
        }
    }

    /**
     * @param signingKey HMAC-SHA256 key for continuation tokens, at least 32 bytes. Every
     *     replica must hold the same key or a walk fails when a load balancer moves it.
     * @param ttl how long an issued cursor stays valid
     */
    public record Cursor(String signingKey, Duration ttl) {

        public static final int MIN_KEY_BYTES = 32;

        public Cursor {
            require(signingKey, "stats.api.cursor.signing-key");
            require(ttl, "stats.api.cursor.ttl");
            if (signingKey.getBytes(StandardCharsets.UTF_8).length < MIN_KEY_BYTES) {
                throw new IllegalArgumentException(
                        "stats.api.cursor.signing-key must be at least " + MIN_KEY_BYTES + " bytes");
            }
            if (ttl.isNegative() || ttl.isZero()) {
                throw new IllegalArgumentException("stats.api.cursor.ttl must be positive");
            }
        }

        /** The key never reaches a log line, an actuator endpoint or an exception message. */
        @Override
        public String toString() {
            return "Cursor[signingKey=<redacted>, ttl=" + ttl + "]";
        }
    }

    private static void require(Object value, String key) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException(key + " must be set; it has no default in code");
        }
    }
}
