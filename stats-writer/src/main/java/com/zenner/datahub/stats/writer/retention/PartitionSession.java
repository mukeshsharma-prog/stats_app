package com.zenner.datahub.stats.writer.retention;

import com.zenner.datahub.stats.common.model.StatType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * One partition-maintenance session: a borrowed connection, an advisory lock, and a
 * session state that is always put back (ADR-003 D3).
 *
 * <p><strong>Why both callers share this rather than each holding their own lock
 * protocol.</strong> D3 names a single lock — {@code hashtext('stats.retention:' ||
 * statType)} — and pre-creation and retention are the two things it serialises. Written
 * twice, a divergence in the key string would leave the two jobs mutually invisible, and
 * the symptom is not an error: two replicas would simply run maintenance at the same time
 * and mostly get away with it, until the day they did not. Written once, the key cannot
 * disagree with itself.</p>
 *
 * <h2>The three pieces of session state, and why each is load-bearing</h2>
 *
 * <ul>
 *   <li><strong>The advisory lock is session-level</strong>, so it outlives the borrow:
 *       the pool keeps the physical session open, and a lock leaked here blocks this stat
 *       type's maintenance on <em>every</em> replica until that connection is evicted.
 *       Release is therefore unconditional, including on the failure path.</li>
 *   <li><strong>The session time zone is set to UTC</strong>, because bound verification
 *       reads {@code pg_get_expr}, which renders {@code timestamptz} in the session zone.
 *       Under any other zone a correct partition reads back as one with the wrong bounds —
 *       so this is correctness, not cosmetics. It is restored because HikariCP does not
 *       reset it and the next borrower would inherit it.</li>
 *   <li><strong>Auto-commit is forced on</strong>, because
 *       {@code ALTER TABLE … DETACH PARTITION … CONCURRENTLY} refuses to run inside a
 *       transaction block — verified against PostgreSQL 17, where it is a hard
 *       {@code ERROR} rather than a degradation. Pre-creation does not need it and is
 *       unharmed by it, so both callers get the same session rather than two subtly
 *       different ones.</li>
 * </ul>
 */
final class PartitionSession {

    private static final String TRY_LOCK_SQL =
            "SELECT pg_try_advisory_lock(hashtext('stats.retention:' || ?))";
    private static final String UNLOCK_SQL =
            "SELECT pg_advisory_unlock(hashtext('stats.retention:' || ?))";

    private final DataSource dataSource;

    PartitionSession(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Work to run while this stat type's maintenance lock is held. */
    @FunctionalInterface
    interface LockedWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /**
     * Runs {@code work} under the type's advisory lock, or returns empty if another
     * replica holds it.
     *
     * <p>Empty is not a failure. ADR-003 D3 makes it the expected outcome on every replica
     * but one, which is why the caller logs it at {@code DEBUG} rather than treating it as
     * a missed run.</p>
     *
     * @param action a short description used only to build the failure message, never
     *     carrying record content
     * @throws IllegalStateException if the connection or the lock bookkeeping fails
     */
    <T> Optional<T> withLock(StatType type, String action, LockedWork<T> work) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(work, "work");

        try (Connection connection = dataSource.getConnection()) {
            if (!tryLock(connection, type)) {
                return Optional.empty();
            }

            // Everything after the lock is acquired runs inside this try, so no path can
            // return the connection to the pool still holding it.
            String previousTimeZone = null;
            boolean previousAutoCommit = connection.getAutoCommit();
            try {
                previousTimeZone = currentTimeZone(connection);
                setTimeZone(connection, "UTC");
                if (!previousAutoCommit) {
                    connection.setAutoCommit(true);
                }
                T result = work.run(connection);
                release(connection, type, previousTimeZone, previousAutoCommit);
                return Optional.of(result);
            } catch (RuntimeException | SQLException failure) {
                // Cleanup is best-effort ONCE we are already failing. Letting it throw
                // from a finally block would REPLACE the real failure with a
                // lock-bookkeeping message, and ADR-003 D1 requires this failure to be
                // loud and diagnosable — it stops maintenance for a whole stat type.
                try {
                    release(connection, type, previousTimeZone, previousAutoCommit);
                } catch (SQLException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to " + action, exception);
        }
    }

    private static boolean tryLock(Connection connection, StatType type) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TRY_LOCK_SQL)) {
            statement.setString(1, type.wireName());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    /** Restores what we changed and drops the lock, in that order. */
    private static void release(
            Connection connection, StatType type, String timeZone, boolean autoCommit)
            throws SQLException {
        // A null zone means we failed before reading the original, so there is nothing to
        // restore — but the lock still has to go.
        if (timeZone != null) {
            setTimeZone(connection, timeZone);
        }
        unlock(connection, type);
        if (!autoCommit) {
            connection.setAutoCommit(false);
        }
    }

    private static void unlock(Connection connection, StatType type) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UNLOCK_SQL)) {
            statement.setString(1, type.wireName());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new SQLException(
                            "PostgreSQL advisory lock was not held for " + type.wireName());
                }
            }
        }
    }

    private static String currentTimeZone(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SHOW TimeZone")) {
            return result.next() ? result.getString(1) : "UTC";
        }
    }

    private static void setTimeZone(Connection connection, String timeZone) throws SQLException {
        // set_config takes the value as a parameter, so the zone name is never
        // concatenated into SQL.
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT set_config('TimeZone', ?, false)")) {
            statement.setString(1, timeZone);
            statement.execute();
        }
    }
}
