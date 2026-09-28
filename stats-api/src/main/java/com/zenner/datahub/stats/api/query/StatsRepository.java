package com.zenner.datahub.stats.api.query;

import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs a {@link StatsQueryBuilder} statement as {@code stats_ro} — the only database
 * identity this module has, and one PostgreSQL itself refuses every write to (rule 2,
 * DL-135). This class adds nothing to the SQL it is given.
 */
public final class StatsRepository {

    private final JdbcTemplate jdbc;
    private final RecordAssembler assembler;

    public StatsRepository(JdbcTemplate jdbc, RecordAssembler assembler) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
    }

    public List<RecordAssembler.Row> query(StatReader reader, StatsQueryBuilder.SqlQuery query) {
        return jdbc.query(
                query.sql(),
                (row, rowNumber) -> assembler.assemble(reader, row),
                query.parameters().toArray());
    }
}
