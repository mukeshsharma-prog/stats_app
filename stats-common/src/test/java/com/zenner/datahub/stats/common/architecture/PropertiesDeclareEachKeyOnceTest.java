package com.zenner.datahub.stats.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No {@code application.properties} may declare the same key twice.
 *
 * <p><strong>Why this is a build failure and not a lint warning.</strong> A
 * {@code .properties} file silently keeps the <em>last</em> definition of a repeated
 * key. Nothing warns: not the parser, not Spring, not the IDE. So a duplicate is
 * invisible until the day someone tunes the first occurrence, redeploys, observes no
 * change, and starts looking for the fault somewhere else entirely — in the broker, in
 * the code, in the deployment — because the file they edited plainly says what they
 * wanted it to say.</p>
 *
 * <p>It is not hypothetical here. {@code stats-writer}'s file declared all four retry
 * keys twice — {@code tier1-delay}, {@code tier2-delay}, {@code tier3-delay} and
 * {@code max-retries} — from two separate comment blocks that each explained the tiers
 * correctly. The values happened to agree, so nothing misbehaved and the duplication
 * survived every review of a file whose own comments insist these values are
 * <em>"externalised here and NEVER hard-coded"</em>. Both halves were right; only the
 * arrangement was wrong, and that is precisely the kind of defect a person re-reading
 * prose does not see (found and removed 8 Sep 2026).</p>
 *
 * <p>Commented-out keys are ignored on purpose. Several are deliberately parked as
 * documentation of a value that is not set yet, and a key that appears once live and
 * once commented is not a duplicate.</p>
 */
class PropertiesDeclareEachKeyOnceTest {

    @Test
    void noPropertiesFileDeclaresTheSameKeyTwice() {
        List<String> offences = new ArrayList<>();

        for (Path file : propertiesFiles()) {
            Map<String, List<Integer>> lines = new LinkedHashMap<>();
            List<String> content = readLines(file);
            for (int index = 0; index < content.size(); index++) {
                String line = content.get(index).strip();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                    continue;
                }
                int separator = indexOfSeparator(line);
                if (separator < 0) {
                    continue;
                }
                String key = line.substring(0, separator).strip();
                lines.computeIfAbsent(key, unused -> new ArrayList<>()).add(index + 1);
            }
            lines.forEach(
                    (key, occurrences) -> {
                        if (occurrences.size() > 1) {
                            offences.add(
                                    MainSourceScan.relativePath(file)
                                            + " declares "
                                            + key
                                            + " on lines "
                                            + occurrences);
                        }
                    });
        }

        assertThat(offences)
                .as(
                        "A .properties file silently keeps the LAST definition of a repeated "
                                + "key, so the earlier one is dead configuration that still reads "
                                + "as live. Editing it changes nothing and the reason is invisible. "
                                + "Delete the duplicate and keep one definition beside its "
                                + "explanation.")
                .isEmpty();
    }

    /** The scan is only a control if it is actually finding the files. */
    @Test
    void scansEveryModulesConfiguration() {
        assertThat(propertiesFiles())
                .as("every application module ships an application.properties")
                .hasSizeGreaterThanOrEqualTo(3);
    }

    /** {@code key=value}, {@code key:value} — the two forms the format allows. */
    private static int indexOfSeparator(String line) {
        int equals = line.indexOf('=');
        int colon = line.indexOf(':');
        if (equals < 0) {
            return colon;
        }
        if (colon < 0) {
            return equals;
        }
        return Math.min(equals, colon);
    }

    private static List<Path> propertiesFiles() {
        List<Path> files = new ArrayList<>();
        for (String module : MainSourceScan.MODULES) {
            Path root = MainSourceScan.repositoryRoot().resolve(module).resolve("src/main/resources");
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".properties"))
                        .sorted()
                        .forEach(files::add);
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
        return files;
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
