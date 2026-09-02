package com.zenner.datahub.stats.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.common.model.StatType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Trap 15 — adding a stat type must not require editing an existing type's code.
 *
 * <p>Only a diff can prove "no existing type's code changed". What this test catches is
 * the specific shape that causes it: main source that names <em>one</em> stat type,
 * either as an enum constant or as a hard-coded wire literal. Every queue name, routing
 * key, schema filename, and table name derives from {@link StatType#wireName()}, so
 * adding an enum constant fans out for free — until someone writes
 * {@code if (type == NETSTATS)} or {@code "lorastats"} somewhere, at which point the
 * next type needs that site edited too and the fan-out is silently gone. (STA-14A,
 * STA-22)</p>
 *
 * <p>This does not forbid special-casing. ADR-003 D2 already requires one: `netstats`
 * is a `jsonb` hybrid where the other four are typed columns, and T-037 will need code
 * that knows it. What it forbids is <strong>undocumented</strong> special-casing —
 * every exception must be listed in {@link #JUSTIFIED_SPECIAL_CASES} with a reason,
 * which turns "someone quietly branched on a type" into a reviewed decision.</p>
 *
 * <p>Java main sources only. Per-type configuration keys such as
 * {@code stats.consumer.lorastats.prefetch} are legitimate and expected — per-type
 * concurrency and prefetch are a MUST (STA-11A) — so properties files are out of
 * scope.</p>
 */
class StatTypeFanOutTest {

    /**
     * Repository-relative paths allowed to name an individual stat type, each with the
     * reason. Adding an entry is the point at which someone has to justify the branch.
     */
    private static final Map<String, String> JUSTIFIED_SPECIAL_CASES = Map.of(
            "stats-common/src/main/java/com/zenner/datahub/stats/common/model/StatType.java",
            "Defines the five constants and their wire literals. This is the one place "
                    + "the identities are allowed to exist.",
            "stats-writer/src/main/java/com/zenner/datahub/stats/writer/consumer/"
                    + "LorastatsConsumerDescriptor.java",
            "T-019 requires one additive consumer artifact per type. The descriptor is "
                    + "the single identity point for that artifact; adding another type "
                    + "adds a sibling descriptor and never edits this one.");

    private static final List<String> MODULES =
            List.of("stats-common", "stats-input", "stats-writer", "stats-api");

    @Test
    void noMainSourceOutsideStatTypeNamesAnIndividualStatType() {
        List<String> offences = new ArrayList<>();

        for (Path file : mainSources()) {
            String relative = relativePath(file);
            if (JUSTIFIED_SPECIAL_CASES.containsKey(relative)) {
                continue;
            }
            for (String mention : mentionsOfASingleType(readCodeWithoutComments(file))) {
                offences.add(relative + " names " + mention);
            }
        }

        assertThat(offences)
                .as(
                        "Main source must derive every per-type name from StatType.wireName(), "
                                + "so a new type is an additive change (STA-22, trap 15). If a "
                                + "branch is genuinely required — ADR-003 D2's netstats jsonb "
                                + "hybrid is the known case — add it to "
                                + "JUSTIFIED_SPECIAL_CASES with the reason.")
                .isEmpty();
    }

    /**
     * A whitelist entry for a file that no longer exists would grant a silent pass to a
     * path someone later recreates. Stale exemptions are how a control stops working
     * without anything failing.
     */
    @Test
    void everyJustifiedSpecialCaseStillExists() {
        for (Map.Entry<String, String> entry : JUSTIFIED_SPECIAL_CASES.entrySet()) {
            assertThat(repositoryRoot().resolve(entry.getKey()))
                    .as("Stale exemption: %s (%s)", entry.getKey(), entry.getValue())
                    .exists();
        }
    }

    @Test
    void scansEveryModuleThatExists() {
        assertThat(mainSources())
                .as("The scan found no Java main sources — it would pass vacuously")
                .isNotEmpty();
    }

    // === Scanning ==========================================================

    private static List<String> mentionsOfASingleType(String code) {
        List<String> found = new ArrayList<>();
        for (StatType type : StatType.values()) {
            if (Pattern.compile("\\b" + type.name() + "\\b").matcher(code).find()) {
                found.add("the constant " + type.name());
            }
            if (code.contains('"' + type.wireName() + '"')) {
                found.add("the literal \"" + type.wireName() + '"');
            }
        }
        return found;
    }

    private static List<Path> mainSources() {
        List<Path> files = new ArrayList<>();
        for (String module : MODULES) {
            Path root = repositoryRoot().resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .forEach(files::add);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        return files;
    }

    /**
     * Strips comments so the rule is about code. A stat type named in prose — a javadoc
     * explaining why `netstats` is a hybrid, or a comment listing the five types — is
     * documentation, not a branch, and failing on it would only teach people to stop
     * writing the explanations.
     */
    private static String readCodeWithoutComments(Path file) {
        String source = read(file);
        StringBuilder code = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';

            if (current == '"' || current == '\'') {
                index = copyLiteral(source, index, current, code);
            } else if (current == '/' && next == '/') {
                while (index < source.length() && source.charAt(index) != '\n') {
                    index++;
                }
            } else if (current == '/' && next == '*') {
                index += 2;
                while (index + 1 < source.length()
                        && !(source.charAt(index) == '*' && source.charAt(index + 1) == '/')) {
                    index++;
                }
                index = Math.min(index + 2, source.length());
            } else {
                code.append(current);
                index++;
            }
        }
        return code.toString();
    }

    /** Copies a string or character literal verbatim, honouring backslash escapes. */
    private static int copyLiteral(String source, int start, char quote, StringBuilder code) {
        code.append(quote);
        int index = start + 1;
        while (index < source.length()) {
            char current = source.charAt(index);
            code.append(current);
            index++;
            if (current == '\\' && index < source.length()) {
                code.append(source.charAt(index));
                index++;
            } else if (current == quote) {
                break;
            }
        }
        return index;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String relativePath(Path file) {
        return repositoryRoot().relativize(file).toString().replace('\\', '/');
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty("stats.repositoryRoot");
        if (configured == null) {
            throw new IllegalStateException(
                    "stats.repositoryRoot is not set. Gradle supplies it in "
                            + "stats-common/build.gradle.kts; run this through Gradle, not a "
                            + "bare IDE runner without that system property.");
        }
        return Path.of(configured);
    }
}
