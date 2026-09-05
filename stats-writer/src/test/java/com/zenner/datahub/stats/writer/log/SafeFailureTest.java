package com.zenner.datahub.stats.writer.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Guard P2 at the unit level (T-066, DEVELOPMENT.md §7).
 *
 * <p>The fixture below is the real thing: a unique violation on the lorastats primary
 * key, with PostgreSQL's {@code DETAIL:} line in the driver's message exactly as the
 * JDBC driver builds it, wrapped by Spring the way the batch writer receives it. Every
 * assertion here is about what must <strong>not</strong> come out.</p>
 */
class SafeFailureTest {

    /** A ts_device and a uid — the two values the DETAIL line of a PK violation prints. */
    private static final String LEAKED_DEVICE_TIME = "1787833739";
    private static final String LEAKED_UID = "CLSCON-11d3ec4c-f6e1-403a-a9ef-b6f5fe909eee";

    private static DataIntegrityViolationException duplicateKeyFailure() {
        SQLException driver =
                new SQLException(
                        "ERROR: duplicate key value violates unique constraint "
                                + "\"lorastats_2026_08_29_pkey\"\n  Detail: Key (ts_device, uid)=("
                                + LEAKED_DEVICE_TIME
                                + ", "
                                + LEAKED_UID
                                + ") already exists.",
                        "23505");
        return new DataIntegrityViolationException("could not execute statement", driver);
    }

    @Test
    @DisplayName("no part of the driver's DETAIL line survives the description")
    void driverDetailNeverSurvives() {
        String description = SafeFailure.describe(duplicateKeyFailure());

        assertThat(description)
                .doesNotContain("Detail")
                .doesNotContain(LEAKED_UID)
                .doesNotContain(LEAKED_DEVICE_TIME)
                .doesNotContain("duplicate key value")
                .doesNotContain("lorastats_2026_08_29_pkey");
    }

    @Test
    @DisplayName("what survives is the type chain and the SQLState — the actionable half")
    void whatSurvivesIsEnoughToActOn() {
        // 23505 is a unique violation. Knowing that, without any value, is what tells an
        // operator this is a duplicate rather than a schema fault or an overflow.
        assertThat(SafeFailure.describe(duplicateKeyFailure()))
                .isEqualTo("DataIntegrityViolationException <- SQLException sqlState=23505");
    }

    @Test
    @DisplayName("a SQLState that is not a five-character code is dropped, not trusted")
    void implausibleSqlStateIsDropped() {
        // The entire reason SQLState may be logged is that it cannot carry a row value.
        // That is an assumption about a driver, so it is checked rather than believed.
        SQLException odd =
                new SQLException("boom", "Key (uid)=(" + LEAKED_UID + ") already exists");

        assertThat(SafeFailure.describe(odd)).isEqualTo("SQLException").doesNotContain(LEAKED_UID);
    }

    @Test
    @DisplayName("our own exception messages are dropped too, not only the driver's")
    void ourOwnMessagesAreDroppedAsWell() {
        // T-057 is about to add throws like this one. The rule is not "no driver
        // messages", it is "no messages" — an application message is no safer.
        assertThat(
                        SafeFailure.describe(
                                new IllegalStateException("uid=" + LEAKED_UID + " failed the schema")))
                .isEqualTo("IllegalStateException")
                .doesNotContain(LEAKED_UID);
    }

    @Test
    @DisplayName("a cause CYCLE terminates instead of hanging the consumer thread")
    void causeCycleTerminates() {
        // The JDK forbids initCause(this), so the trivial self-reference cannot be built
        // — but nothing forbids a -> b -> a, and an unbounded walk over that never
        // returns. This is the version of the guard that can actually happen: a logging
        // helper that hangs a consumer thread is worse than the leak it prevents.
        SQLException first = new SQLException("first", "23505");
        SQLException second = new SQLException("second", "23505");
        first.initCause(second);
        second.initCause(first);

        assertThat(SafeFailure.describe(first).split(" <- ")).hasSize(8);
        assertThat(SafeFailure.rootType(first)).isEqualTo("SQLException");
    }

    @Test
    @DisplayName("a deep chain is bounded rather than unbounded")
    void deepChainIsBounded() {
        Throwable deep = new IllegalStateException("root");
        for (int i = 0; i < 40; i++) {
            deep = new IllegalStateException("layer " + i, deep);
        }

        assertThat(SafeFailure.describe(deep).split(" <- ")).hasSizeLessThanOrEqualTo(8);
    }

    @Test
    @DisplayName("the metric label stays the root type alone, for cardinality")
    void rootTypeIsTheMetricLabel() {
        assertThat(SafeFailure.rootType(duplicateKeyFailure())).isEqualTo("SQLException");
        assertThat(SafeFailure.rootType(null)).isEqualTo("none");
        assertThat(SafeFailure.describe(null)).isEqualTo("none");
    }
}
