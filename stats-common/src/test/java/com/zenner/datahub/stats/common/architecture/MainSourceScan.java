package com.zenner.datahub.stats.common.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads the repository's Java main sources for the architecture guards in this package.
 *
 * <p>Two guards scan the same files for different shapes — {@link StatTypeFanOutTest}
 * for a hard-coded stat type (trap 15) and {@link VersionIsNotADiscriminatorTest} for a
 * read of the envelope's {@code version} (trap 20). They share this scanner so a module
 * added to {@link #MODULES} is covered by both at once. A guard that silently stops
 * scanning a module still passes, which is the failure mode both of them exist to
 * prevent.</p>
 */
final class MainSourceScan {

    /**
     * Every module whose main source is subject to the architecture rules. A new module
     * must be added here; {@code scansEveryModuleThatExists} in each guard is what stops
     * the scan passing vacuously if this list and the repository disagree.
     */
    static final List<String> MODULES =
            List.of("stats-common", "stats-testsupport", "stats-input", "stats-writer", "stats-api");

    private MainSourceScan() {}

    static List<Path> files() {
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
     * Strips comments so every rule in this package is about code. A name in prose — a
     * javadoc explaining why {@code version} is not a discriminator, or a comment listing
     * the five stat types — is documentation, not a branch, and failing on it would only
     * teach people to stop writing the explanations.
     */
    static String codeWithoutComments(Path file) {
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

    static String relativePath(Path file) {
        return repositoryRoot().relativize(file).toString().replace('\\', '/');
    }

    static Path repositoryRoot() {
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
