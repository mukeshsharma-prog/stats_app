package com.zenner.datahub.stats.writer.retry;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.sql.SQLException;
import com.zenner.datahub.stats.writer.log.SafeFailure;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Decides whether a failed delivery is worth another attempt (topology
 * contract §4, "Error classification: explicit mapping from exception category to
 * retry, DLQ, or alert").
 *
 * <p><strong>The default is RETRY, and that direction is the decision.</strong> An
 * unfamiliar exception classified as permanent dead-letters a record that a
 * 30-second wait would have written — and a dead-letter needs a human, a root cause
 * and a redrive, so the cost of guessing wrong that way is hours. Classified as
 * transient, the same record costs at most 35½ minutes of queue time before it
 * reaches the DLQ anyway. Wrong-and-retry is recoverable; wrong-and-dead-letter is
 * work.</p>
 *
 * <p>Three categories are permanent because no amount of waiting changes them:</p>
 *
 * <ul>
 *   <li>{@link PermanentFailureException} — a processor said so explicitly. This is
 *       the channel the payload gate and the dialect range-check use.</li>
 *   <li>{@link JsonProcessingException} — the body is not the JSON we thought. Input
 *       validated the envelope, so this means a shape the model cannot bind, and the
 *       bytes will not change on redelivery.</li>
 *   <li>{@link DataIntegrityViolationException} — a violated invariant: the
 *       {@code clsbox} CHECK, a NOT NULL, a type overflow. Note that a duplicate is
 *       <em>not</em> in this class: {@code ON CONFLICT (ts_device, uid) DO NOTHING}
 *       absorbs it and meters it long before an exception exists.</li>
 *   <li>An {@link SQLException} whose SQLSTATE is class {@code 22} (data exception) or
 *       {@code 54} (program limit exceeded). Both describe the row, not the
 *       database's health: U+0000 in {@code text} (22021) or {@code jsonb} (22P05), an
 *       index row past 2,704 bytes (54000). Spring translates some of these to
 *       {@link DataIntegrityViolationException} and others to transient-looking types —
 *       54000 arrived as {@code DataAccessResourceFailureException} and was retried for
 *       35½ minutes — so the SQLSTATE is read directly rather than through the
 *       translation.</li>
 * </ul>
 */
@Component
public class FailureClassifier {

    /** Whether the failure will recur identically on every future attempt. */
    public boolean isPermanent(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof PermanentFailureException
                    || current instanceof JsonProcessingException
                    || current instanceof DataIntegrityViolationException) {
                return true;
            }
            if (current instanceof SQLException sql && isRowDeterminedSqlState(sql.getSQLState())) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    /** SQLSTATE classes whose verdict depends only on the row being written. */
    static boolean isRowDeterminedSqlState(String sqlState) {
        return sqlState != null
                && sqlState.length() == 5
                && (sqlState.startsWith("22") || sqlState.startsWith("54"));
    }

    /**
     * A short, safe label for the header and the metric tag.
     *
     * <p>The exception's <em>message</em> is deliberately not used: a JDBC or validation
     * message routinely quotes the offending value, and payload content is never echoed
     * — into a response, a log, or a broker header that ops tooling will happily
     * display. {@link SafeFailure} owns that judgement for the whole module; this method
     * is the metric- and header-shaped view of it.</p>
     */
    public String reasonLabel(Throwable failure) {
        return SafeFailure.rootType(failure);
    }
}
