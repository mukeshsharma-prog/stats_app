package com.zenner.datahub.stats.api.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.zenner.datahub.stats.api.query.StatsQueryBuilder.Keyset;
import com.zenner.datahub.stats.api.query.StatsQueryBuilder.SqlQuery;
import com.zenner.datahub.stats.common.model.StatType;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T-028, the read half: the tenant predicate verified against the GENERATED SQL, not a
 * response body (STA-35A, ADR-006). A response can look right for the wrong reason — on
 * every tenant but minol, a filter on {@code maintenant} returns exactly the rows a filter
 * on {@code tenant} does (DL-139). The SQL cannot.
 *
 * <p>Every statement, for every reader, every operation, with and without a cursor.
 * Since DL-172 "every reader" is checked rather than claimed: the list once stopped at
 * three of five types for two weeks after ltestats and netstats were built (T-028 review,
 * finding 2).</p>
 */
class StatsQueryBuilderTest {

    private static final String TENANT = "tenant-alpha";
    private static final Keyset AFTER = new Keyset(1_789_001_000L, "CLSCON-b");

    /** One reader per stat type — the same five beans {@code StatsApiConfiguration} wires. */
    static final List<StatReader> READERS =
            List.of(
                    new LorastatsReader(),
                    new SysstatsReader(),
                    new OmsstatsReader(),
                    new LtestatsReader(),
                    new NetstatsReader());

    static Stream<SqlQuery> everyStatement() {
        return READERS.stream()
                .flatMap(
                        reader ->
                                Stream.of(
                                        StatsQueryBuilder.tenantList(reader, TENANT, 1_789_000_000L, 1_789_003_600L, null, 101),
                                        StatsQueryBuilder.tenantList(reader, TENANT, 1_789_000_000L, 1_789_003_600L, AFTER, 101),
                                        StatsQueryBuilder.device(reader, TENANT, "04b64810652b", 1_789_000_000L, null, null, 101),
                                        StatsQueryBuilder.device(reader, TENANT, "04b64810652b", 1_789_000_000L, null, AFTER, 101),
                                        StatsQueryBuilder.device(reader, TENANT, "04b64810652b", 1_789_000_000L, 1_789_003_600L, AFTER, 101),
                                        StatsQueryBuilder.byUid(reader, TENANT, "CLSCON-a")));
    }

    @ParameterizedTest
    @MethodSource("everyStatement")
    @DisplayName("the first predicate is tenant = ?, bound to the token's tenant")
    void everyStatementIsScopedToTheTokenTenantFirst(SqlQuery query) {
        assertThat(where(query)).startsWith("tenant = ?");
        assertThat(query.parameters().get(0)).isEqualTo(TENANT);
        assertThat(query.parameters().stream().filter(TENANT::equals)).hasSize(1);
    }

    @Test
    @DisplayName("the statements above cover every stat type, so a new reader cannot be left out")
    void everyStatTypeHasItsReaderInThisSuite() {
        EnumSet<StatType> covered = EnumSet.noneOf(StatType.class);
        READERS.forEach(reader -> covered.add(reader.type()));
        assertThat(covered).containsExactlyInAnyOrderElementsOf(Arrays.asList(StatType.values()));
    }

    @ParameterizedTest
    @MethodSource("everyStatement")
    @DisplayName("every condition is joined by AND — 'tenant = ? OR …' starts with tenant = ? and scopes nothing")
    void everyConditionNarrowsTheTenantScope(SqlQuery query) {
        String where = where(query);
        assertThat(where.toLowerCase(Locale.ROOT)).doesNotContain(" or ").doesNotContain(")or").doesNotContain("or(");
        List<String> conditions = Arrays.asList(where.split(" AND "));
        assertThat(conditions.get(0)).isEqualTo("tenant = ?");
        // Parentheses only ever group the keyset's row comparison, never a disjunction.
        String allowed =
                "tenant = \\?"
                        + "|[a-z_]+ (=|>=|<) (\\?|upper\\(\\?\\))"
                        + "|\\(ts_device, uid\\) < \\(\\?, \\?\\)";
        conditions.forEach(condition -> assertThat(condition).matches(allowed));
    }

    @ParameterizedTest
    @MethodSource("everyStatement")
    @DisplayName("maintenant is selected and returned, and never filtered or sorted on")
    void maintenantIsNeverAPredicate(SqlQuery query) {
        String sql = query.sql().toLowerCase(Locale.ROOT);
        assertThat(sql.substring(0, sql.indexOf(" from "))).contains("maintenant");
        assertThat(sql.substring(sql.indexOf(" from "))).doesNotContain("maintenant");
    }

    @ParameterizedTest
    @MethodSource("everyStatement")
    @DisplayName("the order is always ts_device DESC, uid DESC, and the result is always limited")
    void theSortIsFixedAndAlwaysApplied(SqlQuery query) {
        assertThat(query.sql()).contains(" ORDER BY ts_device DESC, uid DESC LIMIT ?");
        assertThat(query.sql()).doesNotContainIgnoringCase("OFFSET");
    }

    @ParameterizedTest
    @MethodSource("everyStatement")
    @DisplayName("clsbox is matched as clsbox = upper(?), which keeps the covering index usable")
    void clsboxIsNormalisedOnTheParameterSide(SqlQuery query) {
        assertThat(query.sql()).doesNotContainIgnoringCase("upper(clsbox)").doesNotContainIgnoringCase("lower(");
    }

    @ParameterizedTest
    @ValueSource(strings = {"x' OR '1'='1", "tenant-alpha; DROP TABLE lorastats", "a' OR maintenant = 'b' --"})
    @DisplayName("no caller-supplied value is ever part of the SQL text")
    void valuesAreBoundNeverInterpolated(String hostile) {
        LorastatsReader reader = new LorastatsReader();
        List<SqlQuery> queries =
                List.of(
                        StatsQueryBuilder.tenantList(reader, hostile, 1L, 2L, new Keyset(1L, hostile), 2),
                        StatsQueryBuilder.device(reader, hostile, hostile, 1L, 2L, null, 2),
                        StatsQueryBuilder.byUid(reader, hostile, hostile));
        for (SqlQuery query : queries) {
            assertThat(query.sql()).doesNotContain(hostile);
        }
    }

    @Test
    @DisplayName("a statement without a tenant cannot be built — not blank, not null")
    void aTenantIsMandatory() {
        LorastatsReader reader = new LorastatsReader();
        assertThatIllegalArgumentException().isThrownBy(() -> StatsQueryBuilder.byUid(reader, null, "u"));
        assertThatIllegalArgumentException().isThrownBy(() -> StatsQueryBuilder.byUid(reader, " ", "u"));
    }

    @Test
    @DisplayName("the open-ended device read has no upper bound at all — never to = now (DL-151)")
    void theOneArgumentDeviceFormIsOpenEnded() {
        SqlQuery query =
                StatsQueryBuilder.device(new LorastatsReader(), TENANT, "04B64810652B", 1_789_000_000L, null, null, 101);
        assertThat(where(query)).doesNotContain("ts_device <").doesNotContainIgnoringCase("now()");
    }

    @Test
    @DisplayName("the keyset continues strictly below the last row on both sort columns")
    void theKeysetIsARowComparison() {
        SqlQuery query =
                StatsQueryBuilder.tenantList(new LorastatsReader(), TENANT, 1_789_000_000L, 1_789_003_600L, AFTER, 101);
        assertThat(query.sql()).contains("AND (ts_device, uid) < (?, ?)");
        assertThat(query.parameters()).contains(AFTER.uid());
    }

    private static String where(SqlQuery query) {
        String sql = query.sql();
        return sql.substring(sql.indexOf(" WHERE ") + " WHERE ".length(), sql.indexOf(" ORDER BY "));
    }
}
