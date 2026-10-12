package com.zenner.datahub.stats.api.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D13, D-10: the container health-checks {@code /actuator/health/readiness}, so the two
 * probe groups must answer without a token. In the default configuration only
 * health is public; PrometheusScrapeAccessIntegrationTest covers the explicit local exception.
 *
 * <p>The second half is the one that matters for ADR-006. A carve-out written as
 * {@code /actuator/**} would expose {@code /actuator/prometheus} and every future
 * endpoint with it; one written as {@code /**} while "getting the healthcheck green"
 * would open the API. Both would pass a test that only asserted the probes.</p>
 *
 * <p>The database and key-server health indicators are disabled here so that readiness is exercised
 * for its <em>authorization</em> outcome without opening a PostgreSQL connection: the
 * assertion is "not 401", and a 503 from a DOWN dependency would satisfy it just as a
 * 200 does. {@link com.zenner.datahub.stats.api.config.ReadinessGroupTest} already
 * guards that {@code db} is in the group.</p>
 */
@SpringBootTest(
        properties = {
            "management.health.db.enabled=false",
            // No key server here: the probe is exercised for its authorization outcome, and
            // ApiSecurityIntegrationTest proves the keycloak contributor against a real one.
            "management.health.keycloak.enabled=false",
            // Boot refuses a readiness group naming a contributor that does not exist,
            // which is the right guard in production and the one thing in the way here.
            "management.endpoint.health.validate-group-membership=false"
        })
@TestPropertySource(locations = "classpath:api-test.properties")
@AutoConfigureMockMvc
class HealthProbesAreUnauthenticatedTest {

    @Autowired private MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
    @DisplayName("the liveness and readiness probes answer without a bearer token")
    void probesAnswerWithoutAToken(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/prometheus", "/actuator/info", "/v1/stats/lorastats", "/"})
    @DisplayName("everything that is not a health probe still requires a bearer token")
    void everythingElseStillRequiresAToken(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }
}
