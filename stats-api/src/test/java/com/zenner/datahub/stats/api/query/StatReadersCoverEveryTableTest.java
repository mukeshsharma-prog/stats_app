package com.zenner.datahub.stats.api.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.api.support.ApiDatabase;
import com.zenner.datahub.stats.api.support.ApiIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The guard that keeps "empty page for a type with no table" honest (DL-162).
 *
 * <p>{@code ltestats} and {@code netstats} read as empty because they have no table yet.
 * The day T-034 or T-037 adds one, this fails until the type's {@link StatReader} is
 * registered — otherwise the API would go on answering every read of a now-populated type
 * with an empty 200, and nothing else would notice. The set of tables is read from the
 * catalogue after the Writer's real migrations, not from a list kept here.</p>
 */
class StatReadersCoverEveryTableTest extends ApiIntegrationTest {

    @Autowired private StatReaders readers;

    @Test
    @DisplayName("every stat-type table the Writer creates has exactly one reader, and every reader a table")
    void readersAndTablesMatch() {
        Set<String> served = new TreeSet<>();
        readers.all().values().forEach(reader -> served.add(reader.table()));
        assertThat(served).isEqualTo(ApiDatabase.partitionedTables());
    }

    @Test
    @DisplayName("every column a reader selects exists in its table")
    void everySelectedColumnExists() throws Exception {
        try (Connection connection = ApiDatabase.owner()) {
            for (StatReader reader : readers.all().values()) {
                StatsQueryBuilder.SqlQuery query = StatsQueryBuilder.byUid(reader, "guard", "guard");
                try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
                    for (int i = 0; i < query.parameters().size(); i++) {
                        statement.setObject(i + 1, query.parameters().get(i));
                    }
                    statement.executeQuery().close();
                }
            }
        }
    }
}
