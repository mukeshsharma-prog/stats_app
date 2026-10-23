package com.zenner.datahub.stats.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guard — the wire-number range check is written once, in {@code WireNumbers}, and no
 * other {@code stats-writer} main source may re-implement it.
 *
 * <p><strong>What went wrong.</strong> Between 8 and 21 September 2026 three writers each
 * grew a private {@code BigInteger} / {@code canConvertToLong} range check, written weeks
 * apart by looking at the one before. They did not agree: one threw an exception the
 * failure classifier treats as transient, so an overflow on that type burned all three
 * retry tiers before it reached the DLQ while the same overflow on another type was
 * permanent on attempt one; another accepted a negative digit string its schema forbids.
 * None of that was visible to any test, because each writer's test only knew its own
 * copy. The tracker had predicted it in as many words: <em>"helpers written weeks apart
 * are how the range check goes missing from one of them."</em></p>
 *
 * <p><strong>The rule.</strong> A {@code stats-writer} <em>persistence</em> source other
 * than {@code WireNumbers} may not name {@code BigInteger}, {@code canConvertToLong},
 * {@code Long.parseLong} or {@code Long.valueOf(String)}. A writer for a new type reads
 * its integers and digit strings through {@code WireNumbers.requiredInteger},
 * {@code optionalInteger}, {@code requiredDigitString} and {@code optionalDigitString};
 * that is the whole of the coercion a type is allowed to have. A member that needs
 * something else (a hex string, a decimal) is a conversation, not a private parser —
 * add it to {@code WireNumbers} with its test, or explain here why it cannot be.</p>
 *
 * <p><strong>Scope.</strong> The persistence package — the writers and the dialect
 * normaliser — because that is where a payload member becomes a column and where the
 * copies grew. The redrive command's reading of the envelope's {@code ts.device} and its
 * CLI argument parse are not payload-to-column coercions and are outside it; a fourth
 * writer that tried to hide a parser in another package would still be caught by
 * {@code <Type>ColumnMappingTest}, which reads the writer's field lists.</p>
 */
class WireNumbersIsTheOnlyCoercionTest {

    private static final String SCOPE =
            "stats-writer/src/main/java/com/zenner/datahub/stats/writer/persistence/";

    private static final String THE_ONE_PLACE =
            "stats-writer/src/main/java/com/zenner/datahub/stats/writer/persistence/WireNumbers.java";

    /** The shapes a hand-rolled range check takes, as seen in the three copies that existed. */
    private static final Pattern PRIVATE_COERCION =
            Pattern.compile(
                    "\\bBigInteger\\b|\\bcanConvertToLong\\s*\\(|\\bLong\\s*\\.\\s*parseLong\\s*\\(|\\bLong\\s*\\.\\s*valueOf\\s*\\(\\s*[A-Za-z_]");

    @Test
    void noWriterMainSourceOtherThanWireNumbersRangeChecksANumber() {
        List<String> offences = new ArrayList<>();
        boolean sawTheOnePlace = false;

        for (Path file : MainSourceScan.files()) {
            String relative = MainSourceScan.relativePath(file);
            if (!relative.startsWith(SCOPE)) {
                continue;
            }
            if (relative.equals(THE_ONE_PLACE)) {
                sawTheOnePlace = true;
                continue;
            }
            var matcher = PRIVATE_COERCION.matcher(MainSourceScan.codeWithoutComments(file));
            while (matcher.find()) {
                offences.add(relative + " uses " + matcher.group());
            }
        }

        assertThat(sawTheOnePlace).as("WireNumbers exists where this guard expects it").isTrue();
        assertThat(offences)
                .as(
                        "The BIGINT range check is WireNumbers' alone. Three "
                                + "private copies written weeks apart disagreed on whether an "
                                + "overflow was permanent and on whether a negative string was "
                                + "admissible, and no test could see it. Read the number through "
                                + "WireNumbers.requiredInteger / optionalInteger / "
                                + "requiredDigitString / optionalDigitString instead — or extend "
                                + "WireNumbers, with a test, if the wire genuinely sends a shape it "
                                + "does not yet read.")
                .isEmpty();
    }

    @Test
    void theScanRecognisesTheShapesItClaimsTo() {
        assertThat(PRIVATE_COERCION.matcher("new BigInteger(text)").find()).isTrue();
        assertThat(PRIVATE_COERCION.matcher("value.canConvertToLong()").find()).isTrue();
        assertThat(PRIVATE_COERCION.matcher("Long.parseLong(text)").find()).isTrue();
        assertThat(PRIVATE_COERCION.matcher("Long.valueOf(text)").find()).isTrue();
        // Boxing a long is not a parse.
        assertThat(PRIVATE_COERCION.matcher("Long.valueOf(42L)").find()).isFalse();
        assertThat(PRIVATE_COERCION.matcher("value.longValue()").find()).isFalse();
    }
}
