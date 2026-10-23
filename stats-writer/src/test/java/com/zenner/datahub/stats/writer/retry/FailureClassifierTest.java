package com.zenner.datahub.stats.writer.retry;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;

/**
 * Which failures skip the three retry tiers.
 *
 * <p>The default is retry, and these tests keep it that way: only failures that the
 * same bytes will reproduce on every attempt are permanent.</p>
 */
class FailureClassifierTest {

    private final FailureClassifier classifier = new FailureClassifier();

    @ParameterizedTest(name = "SQLSTATE {0} is permanent")
    @ValueSource(strings = {"22021", "22P05", "22003", "54000", "54001"})
    @DisplayName("a row-determined SQLSTATE is permanent whatever Spring translated it to")
    void rowDeterminedSqlStatesArePermanent(String sqlState) {
        // 54000 is what an index row over 2,704 bytes raises; Spring translates it to
        // DataAccessResourceFailureException, which used to buy it 35½ minutes of retries
        // for a verdict no retry could change.
        Throwable translated =
                new DataAccessResourceFailureException(
                        "translated", new SQLException("driver message", sqlState));

        assertThat(classifier.isPermanent(translated)).isTrue();
    }

    @ParameterizedTest(name = "SQLSTATE {0} stays transient")
    @ValueSource(strings = {"08006", "40001", "40P01", "57014", "53300"})
    @DisplayName("a SQLSTATE about the database's health stays transient")
    void healthSqlStatesStayTransient(String sqlState) {
        // Connection failure, serialization failure, deadlock, cancelled statement, too
        // many connections: each can succeed on the next attempt.
        Throwable translated =
                new TransientDataAccessResourceException(
                        "translated", new SQLException("driver message", sqlState));

        assertThat(classifier.isPermanent(translated)).isFalse();
    }

    @Test
    @DisplayName("an unfamiliar exception is still RETRIED — the default is the decision")
    void unfamiliarFailuresStayTransient() {
        assertThat(classifier.isPermanent(new IllegalArgumentException("unfamiliar"))).isFalse();
        assertThat(classifier.isPermanent(new QueryTimeoutException("slow"))).isFalse();
        assertThat(classifier.isPermanent(new SQLException("no state"))).isFalse();
    }

    @Test
    @DisplayName("a processor's explicit PermanentFailureException is permanent, wrapped or not")
    void explicitPermanentFailures() {
        PermanentFailureException permanent =
                new PermanentFailureException("version does not fit the record_version column");

        assertThat(classifier.isPermanent(permanent)).isTrue();
        assertThat(classifier.isPermanent(new IllegalStateException("wrapped", permanent))).isTrue();
    }
}
