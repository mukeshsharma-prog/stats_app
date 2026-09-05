package com.zenner.datahub.stats.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guard P2 — no {@code stats-writer} log line may be handed a throwable (T-066,
 * DEVELOPMENT.md §7, DL-114).
 *
 * <p><strong>Why a scan and not a review note.</strong> The rule was adopted on
 * 4 September, written down before the code it governs, and broken anyway — by four
 * separate call sites, one of which had existed since T-021 and was the exact INSERT
 * failure path the guard names. A rule that lives only in a document is enforced by
 * whoever remembers it, and the shape here is copy-shaped: the next {@code ERROR} path
 * gets written by looking at the last one.</p>
 *
 * <p><strong>What leaks.</strong> PostgreSQL puts the offending values in its
 * {@code DETAIL:} line and the JDBC driver puts that line in the exception message, so
 * {@code log.error("…", failure)} prints a row's key values at {@code ERROR} — where
 * {@code DEBUG}-gating the payload never sees it. Once Phase 3 adds {@code ltestats}
 * the same statement prints an IMSI. Use
 * {@code SafeFailure.describe(failure)}, which keeps the type chain and the SQLState
 * and drops every message and stack.</p>
 *
 * <p><strong>Scope is this module deliberately.</strong> {@code stats-writer} is the
 * only module that holds a database connection and a decoded payload at the same time.
 * {@code stats-input} logs broker exceptions — an {@code AmqpConnectException} carries
 * a host and port, not a record — so its stack traces stay, and that is a decision
 * rather than an oversight.</p>
 *
 * <p>Like the other guards in this package this detects a <strong>shape</strong>. It
 * cannot prove an identifier is a {@code Throwable}, so it matches the names failures
 * are actually given here. A future site that legitimately needs one goes in
 * {@link #JUSTIFIED} with its reason — which is the point: it has to be argued in
 * writing.</p>
 */
class WriterLogsCarryNoThrowableTest {

    private static final String MODULE = "stats-writer/";

    /**
     * A logging call whose final argument is a bare identifier of the kind a caught
     * failure is given. The inner alternation tolerates one level of nested parentheses
     * so a call like {@code log.error("… {}", type.wireName(), failure)} still matches.
     */
    private static final Pattern THROWABLE_ARGUMENT =
            Pattern.compile(
                    "\\b(LOGGER|log|logger)\\s*\\.\\s*(error|warn|info|debug|trace)\\s*\\("
                            + "(?:[^()]|\\([^()]*\\))*"
                            + "\\b(failure|exception|throwable|cause|ex|t|e)\\s*\\)",
                    Pattern.DOTALL);

    /** Repository-relative paths allowed to log a throwable, each with its reason. */
    private static final Map<String, String> JUSTIFIED = Map.of();

    @Test
    void noWriterLogStatementIsHandedAThrowable() {
        List<String> offences = new ArrayList<>();

        for (Path file : MainSourceScan.files()) {
            String relative = MainSourceScan.relativePath(file);
            if (!relative.startsWith(MODULE) || JUSTIFIED.containsKey(relative)) {
                continue;
            }
            if (THROWABLE_ARGUMENT.matcher(MainSourceScan.codeWithoutComments(file)).find()) {
                offences.add(relative);
            }
        }

        assertThat(offences)
                .as(
                        "A throwable passed to a logger prints its stack, and a JDBC "
                                + "exception's message carries PostgreSQL's DETAIL: line — the "
                                + "offending row values, at ERROR, where DEBUG-gating the "
                                + "payload never sees them (guard P2, DEVELOPMENT.md §7). Use "
                                + "SafeFailure.describe(failure): it keeps the type chain and "
                                + "the SQLState and drops every message and stack. If a site "
                                + "genuinely needs the stack, add it to JUSTIFIED with the "
                                + "reason it cannot carry a record value.")
                .isEmpty();
    }

    @Test
    void scansTheModuleItClaimsTo() {
        // A guard that silently stops scanning still passes. This is the same vacuity
        // check the other guards in this package carry.
        long scanned = MainSourceScan.files().stream()
                .map(MainSourceScan::relativePath)
                .filter(path -> path.startsWith(MODULE))
                .count();

        assertThat(scanned)
                .as("stats-writer main sources must be on the scan path for this guard to mean anything")
                .isGreaterThan(15);
    }
}
