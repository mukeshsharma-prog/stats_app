package com.zenner.datahub.stats.api.config;

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
 * <p>For the API that is PostgreSQL and Keycloak — and <strong>never the broker</strong>.
 * {@code stats-api} has no RabbitMQ connection at all, so
 * {@code rabbit} appearing in this group would be evidence that decision had been
 * reversed by accident. Its absence here is part of the decision, not an omission.</p>
 *
 * <p>Keycloak is covered: Boot ships no health indicator for an
 * OAuth2 issuer, so {@code KeycloakHealthIndicator} probes the URL the decoder fetches
 * signing keys from. Without it readiness reported UP while every token failed.</p>
 *
 * <p>This asserts the shipped configuration rather than a running context, because the
 * defect it guards is a configuration one: Boot's default readiness group is
 * {@code readinessState} alone, so with the property absent
 * {@code /actuator/health/readiness} reports UP <em>with PostgreSQL down</em> — and
 * {@code docker/docker-compose.yml} health-checks precisely that URL. A read-only API
 * that cannot reach its database would have been reported healthy.</p>
 */
class ReadinessGroupTest {

    private static final String READINESS = "management.endpoint.health.group.readiness.include";
    private static final String LIVENESS = "management.endpoint.health.group.liveness.include";

    private static List<String> group(String key) throws IOException {
        Properties properties = new Properties();
        try (InputStream input =
                ReadinessGroupTest.class.getResourceAsStream("/application.properties")) {
            assertThat(input).as("stats-api application.properties is on the classpath").isNotNull();
            properties.load(input);
        }
        String value = properties.getProperty(key);
        assertThat(value).as("%s must be set explicitly; Boot's default fails open", key).isNotNull();
        return Arrays.stream(value.split(",")).map(String::trim).toList();
    }

    @Test
    @DisplayName("readiness probes PostgreSQL and the key server, and never a broker the API must not hold")
    void readinessProbesTheDatabaseAndNotTheBroker() throws IOException {
        assertThat(group(READINESS))
                .contains("readinessState", "db", "keycloak")
                .doesNotContain("rabbit");
    }

    @Test
    @DisplayName("liveness probes the process only — a dependency here is a restart loop")
    void livenessProbesTheProcessOnly() throws IOException {
        assertThat(group(LIVENESS))
                .containsExactly("livenessState");
    }
}
