package com.zenner.datahub.stats.api.query;

import com.zenner.datahub.stats.common.model.StatType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-type row readers registered by the API.
 *
 * <p>All five types have tables: {@code lorastats}, {@code sysstats},
 * {@code omsstats}, {@code ltestats} and {@code netstats}. Each migrated table
 * has its own reader; the table coverage test guards that pairing. Missing
 * readers still return an empty result to callers of this registry.
 */
public final class StatReaders {

    private final Map<StatType, StatReader> readers = new EnumMap<>(StatType.class);

    public StatReaders(List<StatReader> readers) {
        for (StatReader reader : readers) {
            if (this.readers.put(reader.type(), reader) != null) {
                throw new IllegalStateException("Two readers for " + reader.type().wireName());
            }
        }
    }

    /** The reader for {@code type}, or empty when the type has no table yet. */
    public Optional<StatReader> find(StatType type) {
        return Optional.ofNullable(readers.get(type));
    }

    public Map<StatType, StatReader> all() {
        return Map.copyOf(readers);
    }
}
