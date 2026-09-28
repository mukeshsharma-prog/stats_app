package com.zenner.datahub.stats.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.zenner.datahub.stats.api.support.ApiDatabase;
import com.zenner.datahub.stats.api.support.ApiIntegrationTest;
import com.zenner.datahub.stats.api.support.TestJwks;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * T-043, rule 1, STA-32/STA-32A: POST, PUT, PATCH and DELETE are 405 with {@code Allow:
 * GET} and no side effect — on every {@code /v1/**} route, mapped or not, with a token or
 * without one.
 *
 * <p>"No side effect" is asserted, not assumed: every table's row count is taken before
 * the first request and compared after the last.</p>
 */
class ReadOnlyMethodsIntegrationTest extends ApiIntegrationTest {

    private static final List<String> TABLES = List.of("lorastats", "sysstats", "omsstats");
    private static final List<Long> BEFORE = new ArrayList<>();

    static Stream<Arguments> everyWriteOnEveryRoute() {
        List<String> routes =
                List.of(
                        "/v1/stats/lorastats",
                        "/v1/stats/lorastats/CLSCON-ro-1",
                        "/v1/stats/lorastats/04B64810652B/1789000000",
                        "/v1/stats/lorastats/04B64810652B/1789000000/1789000060",
                        "/v1/stats/wifistats",
                        "/v1/not-a-route",
                        "/v1");
        List<HttpMethod> methods = List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);
        String token = TestJwks.reader("ro-alpha");
        List<Arguments> cases = new ArrayList<>();
        for (String route : routes) {
            for (HttpMethod method : methods) {
                cases.add(Arguments.of(method, route, token));
                cases.add(Arguments.of(method, route, null));
            }
        }
        return cases.stream();
    }

    @BeforeAll
    static void countRows() {
        ApiDatabase.insert(
                "lorastats",
                ApiDatabase.lorastats("CLSCON-ro-1", "ro-alpha", "ro-parent", "04B64810652B", 1_789_000_010L));
        TABLES.forEach(table -> BEFORE.add(ApiDatabase.count(table)));
    }

    @ParameterizedTest(name = "{0} {1} (token: {2})")
    @MethodSource("everyWriteOnEveryRoute")
    @DisplayName("every non-GET is 405 with Allow: GET and a method_not_allowed problem")
    void everyWriteIs405(HttpMethod method, String route, String token) throws Exception {
        MockHttpServletRequestBuilder request =
                MockMvcRequestBuilders.request(method, route)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uid\":\"CLSCON-ro-1\",\"tenant\":\"ro-beta\"}");
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(405);
        assertThat(result.getResponse().getHeader(HttpHeaders.ALLOW)).isEqualTo("GET");
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        JsonNode problem = json(result);
        assertThat(problem.get("type").asText()).isEqualTo("https://stats.example/errors/method_not_allowed");
    }

    @AfterAll
    static void nothingWasWritten() {
        List<Long> after = TABLES.stream().map(ApiDatabase::count).toList();
        assertThat(after).as("row counts of %s", TABLES).isEqualTo(BEFORE);
    }
}
