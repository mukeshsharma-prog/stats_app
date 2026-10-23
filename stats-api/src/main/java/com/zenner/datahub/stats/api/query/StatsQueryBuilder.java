package com.zenner.datahub.stats.api.query;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Every SQL statement the read API runs, built in one place from constants and bound
 * parameters. Pure — no connection — so the
 * isolation suite asserts against the SQL that actually runs rather than against a
 * response body.
 *
 * <p><strong>Rule 3, mechanically.</strong> Every statement's first predicate is
 * {@code tenant = ?}, and its first parameter is the caller's tenant from the token.
 * There is no statement without it: not the list, not the device paths, not the lookup by
 * {@code uid} — a {@code uid} is not isolation, and the key {@code (ts_device, uid)}
 * deliberately leaves {@code tenant} out. {@code maintenant} is selected so it
 * can be returned and appears in no predicate and no ordering: on every tenant but one it
 * equals {@code tenant}, so a filter on it passes every test written from real data;
 * on minol it silently drops rows.</p>
 *
 * <p><strong>What never reaches the SQL text.</strong> Only the table name varies, and it
 * comes from a {@link StatReader} — a closed set — never from the request. Every
 * caller-supplied value is a bind parameter.</p>
 *
 * <p><strong>Two index rules.</strong> {@code clsbox = upper(?)}, never
 * {@code upper(clsbox) = ?}, which would stop {@code (tenant, clsbox, ts_device DESC, uid
 * DESC)} being used. And the order is {@code ts_device DESC, uid DESC} on every
 * statement, with the keyset as a row comparison on the same two columns, so a walk can
 * neither repeat nor skip a row.</p>
 */
public final class StatsQueryBuilder {

    /** Envelope columns, read by {@link RecordAssembler}. */
    static final String ENVELOPE_COLUMNS =
            "uid, tenant, maintenant, clsbox, ts_server, ts_device, record_version, passthrough, data_extra";

    static final String ORDER = " ORDER BY ts_device DESC, uid DESC";

    private StatsQueryBuilder() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** A statement and its bind parameters, in order. */
    public record SqlQuery(String sql, List<Object> parameters) {
        public SqlQuery {
            Objects.requireNonNull(sql, "sql");
            parameters = List.copyOf(parameters);
        }
    }

    /** The last row of the previous page: the walk continues strictly below it. */
    public record Keyset(long tsDevice, String uid) {
        public Keyset {
            Objects.requireNonNull(uid, "uid");
        }
    }

    /** Tenant-wide list: {@code [from, to)} on {@code ts_device}. */
    public static SqlQuery tenantList(
            StatReader reader, String tenant, long from, long to, Keyset after, int fetch) {
        Statement statement = new Statement(reader, tenant);
        statement.and("ts_device >= ?", timestamp(from));
        statement.and("ts_device < ?", timestamp(to));
        return statement.page(after, fetch);
    }

    /**
     * One gateway: {@code ts_device >= from}, and {@code < to} only when {@code to} is
     * given. The one-argument form is open-ended by design, not {@code to = now}:
     * a gateway whose clock runs up to {@code max-clock-skew} fast has records in the near
     * future, and a literal now would hide its newest one on every call.
     */
    public static SqlQuery device(
            StatReader reader,
            String tenant,
            String clsbox,
            long from,
            Long to,
            Keyset after,
            int fetch) {
        Statement statement = new Statement(reader, tenant);
        statement.and("clsbox = upper(?)", Objects.requireNonNull(clsbox, "clsbox"));
        statement.and("ts_device >= ?", timestamp(from));
        if (to != null) {
            statement.and("ts_device < ?", timestamp(to));
        }
        return statement.page(after, fetch);
    }

    /**
     * One record by {@code uid}, within the caller's tenant. A {@code uid} that
     * exists only under another tenant matches nothing here, which is what makes its 404
     * identical to a {@code uid} that exists nowhere. Should one tenant ever hold the
     * same {@code uid} twice (different {@code ts_device}), the newest is returned.
     */
    public static SqlQuery byUid(StatReader reader, String tenant, String uid) {
        Statement statement = new Statement(reader, tenant);
        statement.and("uid = ?", Objects.requireNonNull(uid, "uid"));
        return statement.page(null, 1);
    }

    private static OffsetDateTime timestamp(long epochSeconds) {
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }

    private static final class Statement {
        private final StringBuilder sql = new StringBuilder();
        private final List<Object> parameters = new ArrayList<>();

        Statement(StatReader reader, String tenant) {
            Objects.requireNonNull(reader, "reader");
            if (tenant == null || tenant.isBlank()) {
                // Unreachable behind ReadAccess; a query without a tenant is never built.
                throw new IllegalArgumentException("A read query requires the caller's tenant");
            }
            sql.append("SELECT ")
                    .append(ENVELOPE_COLUMNS)
                    .append(", ")
                    .append(String.join(", ", reader.dataColumns()))
                    .append(" FROM ")
                    .append(reader.table())
                    .append(" WHERE tenant = ?");
            parameters.add(tenant);
        }

        void and(String predicate, Object parameter) {
            sql.append(" AND ").append(predicate);
            parameters.add(parameter);
        }

        SqlQuery page(Keyset after, int fetch) {
            if (after != null) {
                sql.append(" AND (ts_device, uid) < (?, ?)");
                parameters.add(timestamp(after.tsDevice()));
                parameters.add(after.uid());
            }
            sql.append(ORDER).append(" LIMIT ?");
            parameters.add(fetch);
            return new SqlQuery(sql.toString(), parameters);
        }
    }
}
