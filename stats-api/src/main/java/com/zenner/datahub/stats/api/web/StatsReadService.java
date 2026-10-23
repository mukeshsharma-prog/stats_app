package com.zenner.datahub.stats.api.web;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.api.config.ApiProperties;
import com.zenner.datahub.stats.api.cursor.CursorCodec;
import com.zenner.datahub.stats.api.cursor.CursorCodec.Binding;
import com.zenner.datahub.stats.api.cursor.CursorCodec.InvalidCursorException;
import com.zenner.datahub.stats.api.query.RecordAssembler.Row;
import com.zenner.datahub.stats.api.query.StatReader;
import com.zenner.datahub.stats.api.query.StatReaders;
import com.zenner.datahub.stats.api.query.StatsQueryBuilder;
import com.zenner.datahub.stats.api.query.StatsQueryBuilder.Keyset;
import com.zenner.datahub.stats.api.query.StatsRepository;
import com.zenner.datahub.stats.common.error.ErrorCode;
import com.zenner.datahub.stats.common.error.FieldError;
import com.zenner.datahub.stats.common.error.FieldError.SafeMessage;
import com.zenner.datahub.stats.common.model.StatType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The three read operations, and the order their checks run in.
 *
 * <p><strong>Order.</strong> Stat type ({@code unknown_stat_type}) → every parameter
 * ({@code invalid_parameter}, all fields at once) → the range ({@code invalid_time_range},
 * then {@code query_window_exceeded} on the tenant-wide list only) → the cursor
 * ({@code invalid_cursor}) → the query. Nothing reaches the database until the request is
 * wholly valid.</p>
 *
 * <p><strong>The tenant is a parameter of every method and comes only from the
 * token</strong> (via {@code CallerTenant}); the cursor is verified against it, never read
 * for it.</p>
 */
public final class StatsReadService {

    static final String LIST = "list";
    static final String DEVICE_FROM = "device-from";
    static final String DEVICE_RANGE = "device-range";

    private final ApiProperties properties;
    private final StatReaders readers;
    private final StatsRepository repository;
    private final CursorCodec cursors;

    public StatsReadService(
            ApiProperties properties,
            StatReaders readers,
            StatsRepository repository,
            CursorCodec cursors) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.readers = Objects.requireNonNull(readers, "readers");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
    }

    /** {@code GET /v1/stats/{type}?from&to} — tenant-wide, windowed. */
    public StatsPage list(
            String tenant, String rawType, String rawFrom, String rawTo, String rawLimit, String cursor) {
        RequestParameters parameters = RequestParameters.forType(rawType);
        Long from = parameters.epochSeconds("from", rawFrom);
        Long to = parameters.epochSeconds("to", rawTo);
        int limit = limit(parameters, rawLimit);
        parameters.requireValid();
        StatType type = parameters.type();
        requireOrdered(type, from, to);
        long window = to - from;
        if (window > properties.maxQueryWindow().toSeconds()) {
            throw ApiProblemException.validation(
                    ErrorCode.QUERY_WINDOW_EXCEEDED,
                    type,
                    FieldError.of(
                            "to",
                            ErrorCode.QUERY_WINDOW_EXCEEDED.wireCode(),
                            SafeMessage.QUERY_WINDOW_EXCEEDED));
        }
        Binding binding = new Binding(LIST, type, tenant, null, from, to);
        return page(
                binding,
                cursor,
                limit,
                (reader) -> (after) -> StatsQueryBuilder.tenantList(reader, tenant, from, to, after, limit + 1));
    }

    /**
     * {@code GET /v1/stats/{type}/{clsbox}/{from}[/{to}]} — one gateway, no window.
     * {@code rawTo} is {@code null} on the open-ended form.
     */
    public StatsPage device(
            String tenant,
            String rawType,
            String rawClsbox,
            String rawFrom,
            String rawTo,
            String rawLimit,
            String cursor) {
        RequestParameters parameters = RequestParameters.forType(rawType);
        String clsbox = parameters.clsbox(rawClsbox);
        Long from = parameters.epochSeconds("from", rawFrom);
        boolean bounded = rawTo != null;
        Long to = bounded ? parameters.epochSeconds("to", rawTo) : null;
        int limit = limit(parameters, rawLimit);
        parameters.requireValid();
        StatType type = parameters.type();
        if (bounded) {
            requireOrdered(type, from, to);
        }
        Binding binding =
                new Binding(bounded ? DEVICE_RANGE : DEVICE_FROM, type, tenant, clsbox, from, to);
        return page(
                binding,
                cursor,
                limit,
                (reader) -> (after) ->
                        StatsQueryBuilder.device(reader, tenant, clsbox, from, to, after, limit + 1));
    }

    /**
     * {@code GET /v1/stats/{type}/{uid}}. Empty when the caller's tenant holds no
     * such {@code uid} — whether it exists nowhere or under another tenant, which this
     * method cannot tell apart and must not.
     */
    public Optional<ObjectNode> byUid(String tenant, String rawType, String rawUid) {
        RequestParameters parameters = RequestParameters.forType(rawType);
        String uid = parameters.uid(rawUid);
        parameters.requireValid();
        Optional<StatReader> reader = readers.find(parameters.type());
        if (reader.isEmpty()) {
            return Optional.empty();
        }
        List<Row> rows =
                repository.query(reader.get(), StatsQueryBuilder.byUid(reader.get(), tenant, uid));
        return rows.stream().findFirst().map(Row::record);
    }

    private StatsPage page(
            Binding binding,
            String cursor,
            int limit,
            Function<StatReader, Function<Keyset, StatsQueryBuilder.SqlQuery>> query) {
        Keyset after = cursor == null ? null : readCursor(cursor, binding);
        Optional<StatReader> reader = readers.find(binding.type());
        if (reader.isEmpty()) {
            return StatsPage.empty(limit);
        }
        List<Row> rows = repository.query(reader.get(), query.apply(reader.get()).apply(after));
        boolean more = rows.size() > limit;
        List<Row> page = more ? rows.subList(0, limit) : rows;
        String next = more ? cursors.issue(binding, page.get(page.size() - 1).position()) : null;
        return new StatsPage(page.stream().map(Row::record).toList(), next, limit);
    }

    private Keyset readCursor(String cursor, Binding binding) {
        try {
            return cursors.read(cursor, binding);
        } catch (InvalidCursorException invalid) {
            throw ApiProblemException.validation(
                    ErrorCode.INVALID_CURSOR,
                    binding.type(),
                    FieldError.of("cursor", ErrorCode.INVALID_CURSOR.wireCode(), SafeMessage.INVALID_CURSOR));
        }
    }

    private int limit(RequestParameters parameters, String rawLimit) {
        return parameters.limit(rawLimit, properties.defaultPageSize(), properties.maxPageSize());
    }

    /** {@code [from, to)} must be non-empty: {@code from >= to} is {@code invalid_time_range}. */
    private static void requireOrdered(StatType type, long from, long to) {
        if (from >= to) {
            throw ApiProblemException.validation(
                    ErrorCode.INVALID_TIME_RANGE,
                    type,
                    FieldError.of(
                            "from", ErrorCode.INVALID_TIME_RANGE.wireCode(), SafeMessage.TIME_RANGE_INVERTED));
        }
    }
}
