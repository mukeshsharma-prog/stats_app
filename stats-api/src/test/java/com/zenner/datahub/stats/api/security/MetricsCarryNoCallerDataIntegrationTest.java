package com.zenner.datahub.stats.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zenner.datahub.stats.api.support.ApiDatabase;
import com.zenner.datahub.stats.api.support.ApiIntegrationTest;
import com.zenner.datahub.stats.api.support.TestJwks;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * {@code stats.security.prometheus-public-access=true} opens {@code /actuator/prometheus}
 * without a token (DL-166), which is safe only while no series carries anything a caller
 * sent or a tenant owns (DL-167, restoring the guard first written for DL-163's scrape).
 *
 * <p>Every route is exercised with distinctive values — a tenant, a {@code uid}, a
 * {@code clsbox}, a timestamp, a cursor and a hostile query parameter — on every outcome
 * the API has (200, 400, 403, 404, 405), for a type that has always been served and for
 * the two DL-164 added, and then the open scrape is searched for each of them. The
 * request metrics must name the route <em>template</em>; a raw path, or a tag added later
 * that carries the tenant, fails here before it reaches an open endpoint.</p>
 */
@TestPropertySource(properties = "stats.security.prometheus-public-access=true")
@AutoConfigureObservability
class MetricsCarryNoCallerDataIntegrationTest extends ApiIntegrationTest {

    private static final String TENANT = "metrics-tenant-7f3a";
    private static final String UID = "CLSCON-metrics-uid-9c1d";
    private static final String CLSBOX = "00000C0FFEE7";
    private static final long TS = Instant.parse("2026-09-23T10:00:00Z").getEpochSecond();
    private static final String ODD_FROM = "1789999999";
    private static final String HOSTILE = "metrics-hostile-value";

    @Test
    @DisplayName("after requests on every route and outcome, the open scrape holds no tenant, uid, clsbox or raw path")
    void theScrapeCarriesNoCallerData() throws Exception {
        ApiDatabase.insert("lorastats", ApiDatabase.lorastats(UID, TENANT, "metrics-parent", CLSBOX, TS));
        String token = TestJwks.reader(TENANT);

        mvc.perform(get(token, "/v1/stats/lorastats/" + UID)).andExpect(status().isOk());
        mvc.perform(get(token, "/v1/stats/lorastats/CLSCON-metrics-missing")).andExpect(status().isNotFound());
        mvc.perform(get(token, "/v1/stats/lorastats/" + CLSBOX + "/" + ODD_FROM)).andExpect(status().isOk());
        mvc.perform(get(token, "/v1/stats/lorastats/" + CLSBOX + "/" + TS + "/" + (TS + 60)))
                .andExpect(status().isOk());
        mvc.perform(get(token, "/v1/stats/lorastats?from=" + TS + "&to=" + (TS + 60) + "&tenant=" + HOSTILE))
                .andExpect(status().isOk());
        mvc.perform(get(token, "/v1/stats/lorastats?from=" + TS + "&to=" + TS + "&cursor=" + HOSTILE))
                .andExpect(status().isBadRequest());
        mvc.perform(get(token, "/v1/stats/" + HOSTILE + "?from=1&to=2")).andExpect(status().isBadRequest());
        for (String type : List.of("ltestats", "netstats")) {
            mvc.perform(get(token, "/v1/stats/" + type + "/" + UID)).andExpect(status().isNotFound());
            mvc.perform(get(token, "/v1/stats/" + type + "/" + CLSBOX + "/" + ODD_FROM)).andExpect(status().isOk());
        }
        mvc.perform(get(TestJwks.token().tenant(TENANT).roles("none").sign(), "/v1/stats/lorastats/" + UID))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.delete("/v1/stats/lorastats/" + UID))
                .andExpect(status().isMethodNotAllowed());

        String scrape =
                mvc.perform(get(null, "/actuator/prometheus"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(scrape).as("the request metrics exist at all").contains("http_server_requests_seconds_count");
        assertThat(scrape).as("routes are named by template").contains("uri=\"/v1/stats/{type}/{uid}\"");
        for (String value : List.of(TENANT, UID, CLSBOX, ODD_FROM, HOSTILE, "metrics-parent", "CLSCON-metrics-missing")) {
            assertThat(scrape).as("the scrape must not carry %s", value).doesNotContain(value);
        }
    }
}
