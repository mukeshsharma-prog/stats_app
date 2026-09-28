package com.zenner.datahub.stats.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Trap 20 — {@code version} looks like a payload discriminator and is not.
 *
 * <p>Three consecutive production {@code lorastats} records used three mutually
 * incompatible payload shapes, and <strong>all three carried {@code version: 1}</strong>.
 * Nothing on the record says which dialect it is. Since DL-105 all three are accepted and
 * normalised to one typed row, and the dialect is selected <em>structurally</em> — so
 * T-063 introduces real dialect-selection code with {@code version} sitting immediately
 * beside it, looking exactly like the discriminator it is not. (ADR-007 §4 as amended,
 * DL-105 rule 1.)</p>
 *
 * <p>This guard exists <em>before</em> that code rather than after it, deliberately. A
 * guard written once the parser exists gets written to match whatever was built; written
 * first, it has to be argued with. The failure it prevents is silent: branching on
 * {@code version} produces a parser that works perfectly on whichever dialect the author
 * tested and mis-parses the other two into the same typed columns, so the rows are wrong
 * rather than absent and no test fails.</p>
 *
 * <p>Like {@link StatTypeFanOutTest} this detects a <em>shape</em>, not a semantic. It
 * cannot prove an expression is a branch, so it forbids main source outside
 * {@link #JUSTIFIED_READS} from naming the bare identifier at all. Every entry is a
 * reviewed decision with a reason, which is the point: the next site that touches
 * {@code version} has to be justified in writing.</p>
 */
class VersionIsNotADiscriminatorTest {

    /** Matches the bare identifier only. {@code conversion}, {@code record_version} and
     * {@code schemaVersion} are all word-boundary misses, so the rule stays narrow. */
    private static final Pattern BARE_VERSION = Pattern.compile("\\bversion\\b");

    /**
     * Repository-relative paths allowed to name {@code version} in code, each with the
     * reason it is not a dialect branch.
     */
    private static final Map<String, String> JUSTIFIED_READS = Map.of(
            "stats-common/src/main/java/com/zenner/datahub/stats/common/model/StatsEnvelope.java",
            "Declares the record component. The envelope must carry the field to preserve "
                    + "and return it verbatim; declaring it is not reading it.",
            "stats-common/src/main/java/com/zenner/datahub/stats/common/validation/"
                    + "StatsSchemaValidator.java",
            "A DIFFERENT concept that shares the name: the $schema file's own version, "
                    + "read from the schema document and carried on ValidationResult. It "
                    + "never touches the envelope's version.",
            "stats-writer/src/main/java/com/zenner/datahub/stats/writer/persistence/"
                    + "LorastatsBatchWriter.java",
            "Stores it verbatim as the record_version column — the passthrough mapping "
                    + "required by ADR-007. Its only conditional is a SMALLINT range check "
                    + "before conversion, which is a width guard, not a dialect branch.",
            "stats-writer/src/main/java/com/zenner/datahub/stats/writer/persistence/"
                    + "SysstatsBatchWriter.java",
            "The sysstats sibling (T-035, DL-148): the same record_version passthrough "
                    + "and the same SMALLINT width guard. sysstats has one dialect, so "
                    + "there is nothing here a version could even select between.",
            "stats-writer/src/main/java/com/zenner/datahub/stats/writer/persistence/"
                    + "OmsstatsBatchWriter.java",
            "The omsstats sibling (T-036, DL-153): the same record_version passthrough "
                    + "and the same SMALLINT width guard, and again one dialect.",
            "stats-api/src/main/java/com/zenner/datahub/stats/api/query/RecordAssembler.java",
            "The read side of the same passthrough (T-041, DL-162): record_version goes "
                    + "back out verbatim as the envelope's version member. Its only "
                    + "conditional is SQL NULL — whether there is a value to return — and "
                    + "the dialect was normalised away before the row was ever stored.");

    @Test
    void noMainSourceOutsideThePassthroughMappingReadsTheEnvelopeVersion() {
        List<String> offences = new ArrayList<>();

        for (Path file : MainSourceScan.files()) {
            String relative = MainSourceScan.relativePath(file);
            if (JUSTIFIED_READS.containsKey(relative)) {
                continue;
            }
            if (BARE_VERSION.matcher(MainSourceScan.codeWithoutComments(file)).find()) {
                offences.add(relative);
            }
        }

        assertThat(offences)
                .as(
                        "`version` is not a dialect discriminator — all three production "
                                + "lorastats dialects carry version:1 (DL-105 rule 1, trap 20). "
                                + "Dialect selection is STRUCTURAL. If a read is genuinely "
                                + "required, add it to JUSTIFIED_READS with the reason and say "
                                + "in review why it is not a branch.")
                .isEmpty();
    }

    /**
     * A whitelist entry for a file that no longer exists would grant a silent pass to a
     * path someone later recreates. Stale exemptions are how a control stops working
     * without anything failing.
     */
    @Test
    void everyJustifiedReadStillExists() {
        for (Map.Entry<String, String> entry : JUSTIFIED_READS.entrySet()) {
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
}
