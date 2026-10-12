package com.zenner.datahub.stats.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zenner.datahub.stats.api.support.ApiIntegrationTest;
import com.zenner.datahub.stats.api.support.TestJwks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.context.TestPropertySource;

/** The local metrics exception must never become an actuator or stats API exception. */
@TestPropertySource(properties = "stats.security.prometheus-public-access=true")
@AutoConfigureObservability
class PrometheusScrapeAccessIntegrationTest extends ApiIntegrationTest {
    @Test
    void prometheusCanScrapeWithoutAToken() throws Exception {
        var result = mvc.perform(get(null, "/actuator/prometheus"))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("jvm_memory_used_bytes");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/info", "/", "/v1/stats/ltestats", "/v1/stats/netstats"})
    void otherEndpointsStillRequireAuthentication(String path) throws Exception {
        mvc.perform(get(null, path)).andExpect(status().isUnauthorized());
    }

    @Test
    void readsStillRequireTheRoleAndTenant() throws Exception {
        String path = "/v1/stats/netstats?from=1789000000&to=1789000060";
        mvc.perform(get(TestJwks.token().tenant("alpha").roles("other-role").sign(), path))
                .andExpect(status().isForbidden());
        mvc.perform(get(TestJwks.token().sign(), path)).andExpect(status().isForbidden());
    }
}
