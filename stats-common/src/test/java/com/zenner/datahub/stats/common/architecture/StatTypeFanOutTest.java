package com.zenner.datahub.stats.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.common.model.StatType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
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
 * <p>Scanning is shared with the other guards in this package — see
 * {@link MainSourceScan}. Java main sources only. Per-type configuration keys such as
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
                    + "adds a sibling descriptor and never edits this one.",
            "stats-writer/src/main/java/com/zenner/datahub/stats/writer/consumer/"
                    + "SysstatsConsumerDescriptor.java",
            "The sysstats sibling of the above (T-035, DL-148): the one place the "
                    + "sysstats identity is spelled in the Writer.",
            "stats-writer/src/main/java/com/zenner/datahub/stats/writer/consumer/"
                    + "OmsstatsConsumerDescriptor.java",
            "The omsstats sibling (T-036, DL-153): the one place the omsstats identity "
                    + "is spelled in the Writer.");


    @Test
    void noMainSourceOutsideStatTypeNamesAnIndividualStatType() {
        List<String> offences = new ArrayList<>();

        for (Path file : MainSourceScan.files()) {
            String relative = MainSourceScan.relativePath(file);
            if (JUSTIFIED_SPECIAL_CASES.containsKey(relative)) {
                continue;
            }
            for (String mention : mentionsOfASingleType(MainSourceScan.codeWithoutComments(file))) {
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
            assertThat(MainSourceScan.repositoryRoot().resolve(entry.getKey()))
                    .as("Stale exemption: %s (%s)", entry.getKey(), entry.getValue())
                    .exists();
        }
    }

    @Test
    void scansEveryModuleThatExists() {
        assertThat(MainSourceScan.files())
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

}
