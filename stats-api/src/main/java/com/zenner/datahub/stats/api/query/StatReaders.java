package com.zenner.datahub.stats.api.query;

import com.zenner.datahub.stats.common.model.StatType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The stat types this API can read, and the ones it cannot yet.
 *
 * <p>Three of the five types have a table: {@code lorastats}, {@code sysstats},
 * {@code omsstats}. {@code ltestats} (T-034, blocked on D-12 P3) and {@code netstats}
 * (T-037) have none. The contract admits all five, so a read of an unbuilt type is not
 * {@code unknown_stat_type}: it is a valid question with nothing stored to answer it —
 * an empty page, and a 404 for a {@code uid} — which is literally true of the database.
 * {@code StatReadersCoverEveryTableTest} is what stops that answer outliving the reason
 * for it: the migration that creates a type's table fails the build until its reader is
 * registered here (DL-162).</p>
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
