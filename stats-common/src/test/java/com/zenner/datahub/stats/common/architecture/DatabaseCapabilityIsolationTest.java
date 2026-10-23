package com.zenner.datahub.stats.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Source-level guard for the module-to-database capability boundary: only the Writer writes. */
class DatabaseCapabilityIsolationTest {

    private static final Path ROOT = findProjectRoot();

    @Test
    void inputHasNeitherADatabaseDependencyNorDatasourceConfiguration() throws IOException {
        String build = read("stats-input/build.gradle.kts");
        String properties = read("stats-input/src/main/resources/application.properties");

        assertThat(productionDeclarations(build))
                .doesNotContain("starter.jdbc", "postgresql", "flyway");
        assertThat(properties).doesNotContain("spring.datasource", "POSTGRES_");
    }

    @Test
    void composeDoesNotGiveDatabaseCredentialsToInputOrWriterCredentialsToApi()
            throws IOException {
        String compose = read("docker/docker-compose.yml");
        String input = service(compose, "stats-input", "stats-writer");
        String writer = service(compose, "stats-writer", "stats-api");
        String api = service(compose, "stats-api", "volumes");

        assertThat(input)
                .contains(
                        "POSTGRES_URL: \"\"",
                        "POSTGRES_DB: \"\"",
                        "POSTGRES_USER: \"\"",
                        "POSTGRES_PASSWORD: \"\"",
                        "POSTGRES_RO_USER: \"\"",
                        "POSTGRES_RO_PASSWORD: \"\"");
        assertThat(writer)
                .contains("POSTGRES_RO_USER: \"\"", "POSTGRES_RO_PASSWORD: \"\"");
        assertThat(api).contains("POSTGRES_USER: \"\"", "POSTGRES_PASSWORD: \"\"");
    }

    private static String productionDeclarations(String build) {
        return build.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("implementation(") || line.startsWith("runtimeOnly("))
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private static String service(String compose, String name, String nextName) {
        int start = compose.indexOf("  " + name + ":");
        String boundary = nextName.equals("volumes") ? "\nvolumes:" : "\n  " + nextName + ":";
        int end = compose.indexOf(boundary, start + 1);
        assertThat(start).as("service %s exists", name).isGreaterThanOrEqualTo(0);
        assertThat(end).as("service boundary after %s exists", name).isGreaterThan(start);
        return compose.substring(start, end);
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(ROOT.resolve(relativePath));
    }

    private static Path findProjectRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.exists(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("Could not locate project root from working directory");
        }
        return candidate;
    }
}
