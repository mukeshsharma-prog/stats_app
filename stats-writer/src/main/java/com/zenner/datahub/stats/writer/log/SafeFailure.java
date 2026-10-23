package com.zenner.datahub.stats.writer.log;

import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * Renders a failure for a log line without any value the record carried.
 *
 * <h2>What it is protecting against</h2>
 *
 * <p>PostgreSQL puts the offending values in its {@code DETAIL:} line, and the JDBC
 * driver puts that line in the exception's <em>message</em> — so
 * {@code log.error("...", failure)}, {@code failure.getMessage()} and
 * {@code failure.toString()} all print it. A unique-violation on the lorastats table
 * prints {@code Detail: Key (ts_device, uid)=(1787833739, CLSCON-…) already exists},
 * and once Phase 3 adds {@code ltestats} the same path prints an IMSI. That content
 * reaches a log at {@code ERROR}, which is why {@code DEBUG}-gating the payload —
 * the rule in the first half of §7 — never sees it.</p>
 *
 * <h2>Why the rule here is absolute rather than "no driver messages"</h2>
 *
 * <p>The narrow rule would be "log the exception type and message, just not the
 * driver's detail line". It is not implementable: for a {@code PSQLException} the
 * detail <em>is</em> part of the message, and there is no substring of it that is
 * reliably value-free. And our own exceptions are no safer — a processor is free to
 * throw {@code new PermanentFailureException("uid=… failed the schema")}, and the payload
 * gate throws exactly that kind of exception. So no message and no stack trace ever
 * reaches a log line from this module.</p>
 *
 * <h2>What survives, and why it is enough</h2>
 *
 * <p>The causal chain of <strong>type names</strong>, plus the {@code SQLState} when
 * there is a SQL exception anywhere in it. SQLState is the diagnostic that actually
 * matters — {@code 23505} is a unique violation, {@code 22003} a numeric overflow,
 * {@code 23514} a violated CHECK — and it is a five-character code from the standard,
 * carrying no values by construction. {@code IllegalStateException <- SQLException
 * sqlState=23505} says more about what to do next than a stack trace does, and says
 * nothing about the record. The stack is not lost so much as relocated: the record
 * itself is on the dead-letter queue, with its own headers, for a person to inspect
 * under whatever access control the DLQ has rather than under the log
 * retention policy that does not exist yet.</p>
 */
public final class SafeFailure {

    /** Bounds a pathological cause chain; nothing real nests this deep. */
    private static final int MAX_DEPTH = 8;

    /**
     * SQLState is five alphanumerics in the standard. Validated rather than trusted:
     * the whole value of including it is that it cannot carry a row value, and that is
     * an assumption about a driver rather than about our own code.
     */
    private static final Pattern SQL_STATE = Pattern.compile("[0-9A-Za-z]{5}");

    private SafeFailure() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * The causal chain of exception types, plus {@code sqlState} when one is present.
     *
     * <p>Example: {@code DataIntegrityViolationException <- SQLException sqlState=23505}.
     * Safe to log at any level and to put in a broker header.</p>
     */
    public static String describe(Throwable failure) {
        if (failure == null) {
            return "none";
        }

        StringBuilder description = new StringBuilder(64);
        String sqlState = null;

        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            if (depth > 0) {
                description.append(" <- ");
            }
            description.append(current.getClass().getSimpleName());

            if (sqlState == null && current instanceof SQLException sqlException) {
                sqlState = safeSqlState(sqlException.getSQLState());
            }

            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }

        if (sqlState != null) {
            description.append(" sqlState=").append(sqlState);
        }
        return description.toString();
    }

    /**
     * The simple name of the root cause — the label used for the
     * {@code records_dead_lettered{reason}} tag and the {@code x-stats-failure} header.
     *
     * <p>A metric tag has to stay low-cardinality, so it is the root type alone rather
     * than {@link #describe}'s chain.</p>
     */
    public static String rootType(Throwable failure) {
        if (failure == null) {
            return "none";
        }
        // Bounded by MAX_DEPTH, not by "until the cause is null". The JDK forbids
        // initCause(this), but nothing forbids a CYCLE — a caused by b caused by a — and
        // an unbounded walk over one never returns. A logging helper that can hang the
        // consumer thread is worse than the leak it was written to prevent.
        Throwable root = failure;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Throwable cause = root.getCause();
            if (cause == null || cause == root) {
                break;
            }
            root = cause;
        }
        return root.getClass().getSimpleName();
    }

    private static String safeSqlState(String sqlState) {
        return sqlState != null && SQL_STATE.matcher(sqlState).matches() ? sqlState : null;
    }
}
