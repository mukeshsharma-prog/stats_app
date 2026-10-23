package com.zenner.datahub.stats.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guard — no {@code stats-writer} log line may be handed a throwable.
 *
 * <p><strong>Why a scan and not a review note.</strong> The rule was adopted on
 * 4 September, written down before the code it governs, and broken anyway — by four
 * separate call sites, one of which had existed since the first Writer and was the exact INSERT
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
 *
 * <p><strong>Both logging shapes are covered.</strong> Structured logging moved every application log
 * statement to the SLF4J fluent API, and a guard that only recognised
 * {@code log.error("…", e)} would have been silently switched off by that change:
 * {@code log.atError().log("…", e)}, {@code .addKeyValue("cause", e)} and
 * {@code .setCause(e)} all print the same stack. The fluent scan is a small balanced-
 * parenthesis walk rather than a regex, because a chain such as
 * {@code .addKeyValue("cause", SafeFailure.describe(failure))} nests two levels deep and
 * must <em>pass</em> — {@code failure} there is an argument to {@code describe}, not to
 * the logger.</p>
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

    /** The start of a fluent statement: {@code LOGGER.atError()} and friends. */
    private static final Pattern FLUENT_START =
            Pattern.compile(
                    "\\b(LOGGER|log|logger)\\s*\\.\\s*at(Error|Warn|Info|Debug|Trace)\\s*\\(\\s*\\)");

    /** Fluent calls whose argument, if a throwable, reaches the appender. */
    private static final Pattern FLUENT_SINK =
            Pattern.compile("\\.\\s*(log|addKeyValue|addArgument|setCause)\\s*\\(");

    private static final Set<String> THROWABLE_NAMES =
            Set.of("failure", "exception", "throwable", "cause", "ex", "t", "e");

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
            String code = MainSourceScan.codeWithoutComments(file);
            if (THROWABLE_ARGUMENT.matcher(code).find() || fluentStatementHandsOverAThrowable(code)) {
                offences.add(relative);
            }
        }

        assertThat(offences)
                .as(
                        "A throwable passed to a logger prints its stack, and a JDBC "
                                + "exception's message carries PostgreSQL's DETAIL: line — the "
                                + "offending row values, at ERROR, where DEBUG-gating the "
                                + "payload never sees them. Use "
                                + "SafeFailure.describe(failure): it keeps the type chain and "
                                + "the SQLState and drops every message and stack. If a site "
                                + "genuinely needs the stack, add it to JUSTIFIED with the "
                                + "reason it cannot carry a record value.")
                .isEmpty();
    }

    @Test
    void theFluentScanRecognisesEachShapeItClaimsTo() {
        // A guard extended without a check on the extension is a guard that may not
        // have been extended at all. Each shape below is the one the javadoc names.
        assertThat(fluentStatementHandsOverAThrowable(
                "LOGGER.atError().addKeyValue(\"event\", \"x\").log(\"failed\", failure);"))
                .as("a throwable as the last .log() argument").isTrue();
        assertThat(fluentStatementHandsOverAThrowable(
                "log.atWarn().addKeyValue(\"cause\", e).log(\"failed\");"))
                .as("a throwable as an addKeyValue value").isTrue();
        assertThat(fluentStatementHandsOverAThrowable(
                "logger.atError().setCause(exception).log(\"failed\");"))
                .as("setCause").isTrue();
        assertThat(fluentStatementHandsOverAThrowable(
                "LOGGER.atError()\n"
                        + "        .addKeyValue(\"event\", \"write_failed\")\n"
                        + "        .addKeyValue(\"cause\", SafeFailure.describe(failure))\n"
                        + "        .log(\"write failed; see cause\");"))
                .as("SafeFailure.describe(failure) is the sanctioned form and must pass")
                .isFalse();
        assertThat(fluentStatementHandsOverAThrowable(
                "LOGGER.atInfo().addKeyValue(\"type\", type.wireName()).log(\"ok\");"))
                .as("an ordinary fluent statement").isFalse();
    }

    /**
     * Walks each fluent statement with balanced parentheses and asks whether any sink
     * call receives a bare throwable identifier as a <em>direct</em> argument.
     */
    static boolean fluentStatementHandsOverAThrowable(String code) {
        Matcher start = FLUENT_START.matcher(code);
        while (start.find()) {
            String statement = code.substring(start.end(), endOfStatement(code, start.end()));
            Matcher sink = FLUENT_SINK.matcher(statement);
            while (sink.find()) {
                int open = sink.end() - 1;
                int close = matchingParenthesis(statement, open);
                if (close < 0) {
                    break;
                }
                for (String argument : topLevelArguments(statement.substring(open + 1, close))) {
                    if (THROWABLE_NAMES.contains(argument.trim())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Index of the {@code ;} that ends the statement starting at {@code from}, ignoring literals. */
    private static int endOfStatement(String code, int from) {
        int depth = 0;
        for (int index = from; index < code.length(); index++) {
            char current = code.charAt(index);
            if (current == '"' || current == '\'') {
                index = endOfLiteral(code, index);
            } else if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
            } else if (current == ';' && depth <= 0) {
                return index;
            }
        }
        return code.length();
    }

    private static int matchingParenthesis(String code, int open) {
        int depth = 0;
        for (int index = open; index < code.length(); index++) {
            char current = code.charAt(index);
            if (current == '"' || current == '\'') {
                index = endOfLiteral(code, index);
            } else if (current == '(') {
                depth++;
            } else if (current == ')' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static List<String> topLevelArguments(String arguments) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int begin = 0;
        for (int index = 0; index < arguments.length(); index++) {
            char current = arguments.charAt(index);
            if (current == '"' || current == '\'') {
                index = endOfLiteral(arguments, index);
            } else if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
            } else if (current == ',' && depth == 0) {
                parts.add(arguments.substring(begin, index));
                begin = index + 1;
            }
        }
        parts.add(arguments.substring(begin));
        return parts;
    }

    /** Index of the closing quote of the literal opening at {@code open}. */
    private static int endOfLiteral(String code, int open) {
        char quote = code.charAt(open);
        for (int index = open + 1; index < code.length(); index++) {
            char current = code.charAt(index);
            if (current == '\\') {
                index++;
            } else if (current == quote) {
                return index;
            }
        }
        return code.length();
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
