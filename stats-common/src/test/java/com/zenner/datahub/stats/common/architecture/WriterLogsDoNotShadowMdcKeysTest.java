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
 * Guard — no {@code stats-writer} log statement may add a key the delivery's MDC scope
 * already owns (DL-150).
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
 * is enforced where the code is written, like the other guards in this package.</p>
 *
 * <p><strong>Scope.</strong> The four delivery-only keys — {@code uid}, {@code tenant},
 * {@code clsbox}, {@code traceId} — are forbidden anywhere in {@code stats-writer}: nothing
 * outside a delivery has a record to name. {@code statType} and {@code errorCode} are
 * also MDC keys, but maintenance and polling run on their own threads with no scope open,
 * and there the key-value pair is the only way to say which type a partition or a poll
 * belonged to. Those files are listed in {@link #NO_DELIVERY_SCOPE} with the reason; a new
 * entry has to argue, in writing, that its code never runs inside a delivery.</p>
 */
class WriterLogsDoNotShadowMdcKeysTest {

    private static final String MODULE = "stats-writer/";

    /** MDC keys owned by {@code WriterLogContext} that only a delivery can carry. */
    private static final Set<String> DELIVERY_ONLY_KEYS = Set.of("uid", "tenant", "clsbox", "traceId");

    /** MDC keys a non-delivery thread may legitimately add, because no scope is open. */
    private static final Set<String> SCOPE_OR_THREAD_KEYS = Set.of("statType", "errorCode");

    /**
     * Repository-relative paths whose logging never runs under a delivery scope, each
     * with the reason. They may add {@code statType} and {@code errorCode}; they may
     * still never add a delivery-only key.
     */
    private static final Map<String, String> NO_DELIVERY_SCOPE =
            Map.of(
                    "stats-writer/src/main/java/com/zenner/datahub/stats/writer/retention/PartitionMaintenance.java",
                    "scheduled maintenance pass on the scheduler thread; no delivery is open",
                    "stats-writer/src/main/java/com/zenner/datahub/stats/writer/retention/PartitionRetention.java",
                    "called only from PartitionMaintenance, same thread, no delivery open",
                    "stats-writer/src/main/java/com/zenner/datahub/stats/writer/metrics/OperationalMetrics.java",
                    "broker poll on stats.metrics.poll-interval; no delivery is open");

    private static final Pattern ADD_KEY_VALUE =
            Pattern.compile("\\.\\s*addKeyValue\\s*\\(\\s*\"([A-Za-z_]+)\"");

    @Test
    void noWriterLogStatementAddsAKeyTheMdcScopeOwns() {
        List<String> offences = new ArrayList<>();

        for (Path file : MainSourceScan.files()) {
            String relative = MainSourceScan.relativePath(file);
            if (!relative.startsWith(MODULE)) {
                continue;
            }
            boolean mayAddThreadKeys = NO_DELIVERY_SCOPE.containsKey(relative);
            Matcher matcher = ADD_KEY_VALUE.matcher(MainSourceScan.codeWithoutComments(file));
            while (matcher.find()) {
                String key = matcher.group(1);
                if (DELIVERY_ONLY_KEYS.contains(key)
                        || (!mayAddThreadKeys && SCOPE_OR_THREAD_KEYS.contains(key))) {
                    offences.add(relative + " adds \"" + key + "\"");
                }
            }
        }

        assertThat(offences)
                .as(
                        "The delivery's MDC scope (WriterLogContext) already carries this key on "
                                + "every line. Adding it again with addKeyValue is not redundancy "
                                + "but loss: the ECS encoder throws on a duplicate member and "
                                + "logback drops the whole line, silently after the fourth "
                                + "(DL-150). Remove the pair — or, for a file that provably "
                                + "never runs inside a delivery, add it to NO_DELIVERY_SCOPE "
                                + "with the reason.")
                .isEmpty();
    }

    @Test
    void everyWhitelistedFileStillExists() {
        // A whitelist entry for a file that has moved is a rule that has silently
        // stopped applying to wherever the code went.
        List<String> present = new ArrayList<>();
        for (Path file : MainSourceScan.files()) {
            present.add(MainSourceScan.relativePath(file));
        }
        assertThat(present).containsAll(NO_DELIVERY_SCOPE.keySet());
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
}
