package com.zenner.datahub.stats.input.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Readiness probes <em>only this module's real dependencies</em>.
 *
 * <p>For Input that is the <strong>broker and nothing else</strong>. The rule that only the Writer writes
 * says Modules 1 and 3 never hold a write-capable database dependency, and Input holds
 * no database dependency at all — so a {@code db} indicator appearing in this group
 * would mean the rule had been broken somewhere upstream, not that the probe had got
 * more thorough.</p>
 *
 * <p>This asserts the shipped configuration rather than a running context, because the
 * defect it guards is a configuration one: Boot's default readiness group is
 * {@code readinessState} alone, so with the property absent
 * {@code /actuator/health/readiness} reports UP <em>with the broker down</em> — and
 * {@code docker/docker-compose.yml} health-checks precisely that URL. The failure is
 * open and silent, and deleting one line is all it takes to reintroduce it.</p>
 */
class ReadinessGroupTest {

    private static final String READINESS = "management.endpoint.health.group.readiness.include";
    private static final String LIVENESS = "management.endpoint.health.group.liveness.include";

    private static List<String> group(String key) throws IOException {
        Properties properties = new Properties();
        try (InputStream input =
                ReadinessGroupTest.class.getResourceAsStream("/application.properties")) {
            assertThat(input).as("stats-input application.properties is on the classpath").isNotNull();
            properties.load(input);
        }
        String value = properties.getProperty(key);
        assertThat(value).as("%s must be set explicitly; Boot's default fails open", key).isNotNull();
        return Arrays.stream(value.split(",")).map(String::trim).toList();
    }

    @Test
    @DisplayName("readiness probes the broker, and never a database Input does not have")
    void readinessProbesTheBrokerOnly() throws IOException {
        assertThat(group(READINESS))
                .contains("readinessState", "rabbit")
                .doesNotContain("db");
    }

    @Test
    @DisplayName("liveness probes the process only — a dependency here is a restart loop")
    void livenessProbesTheProcessOnly() throws IOException {
        assertThat(group(LIVENESS))
                .containsExactly("livenessState");
    }
}
