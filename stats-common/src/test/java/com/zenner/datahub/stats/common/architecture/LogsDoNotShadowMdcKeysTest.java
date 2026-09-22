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
 * Guard — no log statement in a module that logs ECS may add a key its MDC scope already
 * owns (DL-150; extended to {@code stats-input} on 22 Sep 2026).
 *
 * <p><strong>What went wrong.</strong> {@code WriterLogContext} puts {@code traceId},
 * {@code uid}, {@code statType}, {@code tenant}, {@code clsbox} and {@code errorCode}
 * into the MDC for the whole of a delivery (T-005, DL-134). Seven statements on the
 * consume path then added {@code uid} — and some {@code statType} and {@code clsbox} —
 * a second time with {@code addKeyValue}. Spring Boot's ECS formatter writes MDC entries
 * and key-value pairs into the same JSON object and refuses a duplicate member:
 * {@code IllegalStateException: Duplicate nested pairs added under 'uid'}. Logback
 * catches that inside the appender, reports it to its status manager, and <em>drops the
 * line</em>. After the fourth such report it stops reporting. So every
 * {@code payload_rejected}, {@code expired_on_arrival}, {@code upstream_status_not_ok}
 * and {@code write_failed} line in the Writer was being discarded, with nothing on the
 * console to say so and every counter reading healthy.</p>
 *
 * <p><strong>Why a scan.</strong> The collision is invisible to a unit test: a test
 * logger has no ECS encoder, so the statement "works". It only fails under the production
 * appender, in a process nobody is reading the status of. The shape is also copy-shaped —
 * every one of the seven sites was written by looking at the one before it. So the rule
 * is enforced where the code is written, like the other guards in this package.
 * {@code EcsEncoderDropsDuplicateKeysTest} in {@code stats-writer} characterises the
 * mechanism against the real encoder, so this guard's reason stays executable.</p>
 *
 * <p><strong>The rule.</strong> Each ECS module has one scope class and one set of MDC
 * keys ({@link #MDC_KEYS_BY_MODULE}). A file that runs under that scope adds
 * <em>none</em> of them — the scope already carries the value on every line. A file that
 * provably <em>never</em> runs under the scope — a scheduler thread, a broker poll, a
 * publisher-confirm callback — may add any of them, because there a pair is the only way
 * to say which record or type the line is about; such files are listed in
 * {@link #NO_SCOPE_EVER} with the reason, and a new entry has to argue that in writing.
 * The rule is about the thread, not the key: a key that is safe on one thread is fatal on
 * the other, which is why the earlier two-tier key split was replaced.</p>
 */
class LogsDoNotShadowMdcKeysTest {

    /** Every module that renders {@code logging.structured.format.console=ecs}, with its scope's MDC keys. */
    private static final Map<String, Set<String>> MDC_KEYS_BY_MODULE =
            Map.of(
                    // WriterLogContext
                    "stats-writer/",
                    Set.of("traceId", "uid", "statType", "tenant", "clsbox", "errorCode"),
                    // IngestLogContext
                    "stats-input/",
                    Set.of("traceId", "uid", "statType", "tenant", "clsbox", "errorCode", "ingestSource"));

    /**
     * Repository-relative paths whose logging never runs under the module's scope, each
     * with the reason. They may add the module's MDC keys as pairs; nothing else may.
     */
    private static final Map<String, String> NO_SCOPE_EVER =
            Map.of(
                    "stats-writer/src/main/java/com/zenner/datahub/stats/writer/retention/PartitionMaintenance.java",
                    "scheduled maintenance pass on the scheduler thread; no delivery is open",
                    "stats-writer/src/main/java/com/zenner/datahub/stats/writer/retention/PartitionRetention.java",
                    "called only from PartitionMaintenance, same thread, no delivery open",
                    "stats-writer/src/main/java/com/zenner/datahub/stats/writer/metrics/OperationalMetrics.java",
                    "broker poll on stats.metrics.poll-interval; no delivery is open",
                    "stats-input/src/main/java/com/zenner/datahub/stats/input/publish/InputAmqpConfiguration.java",
                    "ReturnsCallback on the broker's connection thread (ADR-002 §9); the HTTP "
                            + "request scope that published the message is not on this thread");

    private static final Pattern ADD_KEY_VALUE =
            Pattern.compile("\\.\\s*addKeyValue\\s*\\(\\s*\"([A-Za-z_]+)\"");

    @Test
    void noScopedLogStatementAddsAKeyTheMdcScopeOwns() {
        List<String> offences = new ArrayList<>();

        for (Path file : MainSourceScan.files()) {
            String relative = MainSourceScan.relativePath(file);
            Set<String> mdcKeys = mdcKeysFor(relative);
            if (mdcKeys == null || NO_SCOPE_EVER.containsKey(relative)) {
                continue;
            }
            Matcher matcher = ADD_KEY_VALUE.matcher(MainSourceScan.codeWithoutComments(file));
            while (matcher.find()) {
                if (mdcKeys.contains(matcher.group(1))) {
                    offences.add(relative + " adds \"" + matcher.group(1) + "\"");
                }
            }
        }

        assertThat(offences)
                .as(
                        "The module's MDC scope already carries this key on every line. Adding "
                                + "it again with addKeyValue is not redundancy but loss: the ECS "
                                + "encoder throws on a duplicate member and logback drops the "
                                + "whole line, silently after the fourth (DL-150). Remove the "
                                + "pair — or, for a file that provably never runs under the "
                                + "scope, add it to NO_SCOPE_EVER with the reason.")
                .isEmpty();
    }

    @Test
    void everyWhitelistedFileStillExistsInAnEcsModule() {
        // A whitelist entry for a file that has moved is a rule that has silently
        // stopped applying to wherever the code went.
        List<String> present = new ArrayList<>();
        for (Path file : MainSourceScan.files()) {
            present.add(MainSourceScan.relativePath(file));
        }
        assertThat(present).containsAll(NO_SCOPE_EVER.keySet());
        for (String path : NO_SCOPE_EVER.keySet()) {
            assertThat(mdcKeysFor(path)).as(path + " is in a module this guard covers").isNotNull();
        }
    }

    @Test
    void everyEcsModuleIsScanned() {
        // The scan must cover both modules that render ECS, or the guard passes vacuously
        // for the one it forgot. MainSourceScan.MODULES is the list of what exists.
        for (String module : MDC_KEYS_BY_MODULE.keySet()) {
            assertThat(MainSourceScan.MODULES).contains(module.substring(0, module.length() - 1));
        }
    }

    @Test
    void theScanRecognisesTheShapeItClaimsTo() {
        assertThat(ADD_KEY_VALUE.matcher("LOGGER.atWarn().addKeyValue(\"uid\", envelope.uid())").find())
                .isTrue();
        Matcher spaced = ADD_KEY_VALUE.matcher("log\n        .addKeyValue( \"clsbox\" , x)");
        assertThat(spaced.find()).isTrue();
        assertThat(spaced.group(1)).isEqualTo("clsbox");
        assertThat(ADD_KEY_VALUE.matcher("MDC.put(\"uid\", value)").find())
                .as("MDC.put is the sanctioned way to set these keys and must pass")
                .isFalse();
    }

    private static Set<String> mdcKeysFor(String relativePath) {
        for (Map.Entry<String, Set<String>> module : MDC_KEYS_BY_MODULE.entrySet()) {
            if (relativePath.startsWith(module.getKey())) {
                return module.getValue();
            }
        }
        return null;
    }
}
