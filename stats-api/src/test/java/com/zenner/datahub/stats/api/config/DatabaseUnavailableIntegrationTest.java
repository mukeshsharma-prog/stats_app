package com.zenner.datahub.stats.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import com.zenner.datahub.stats.api.support.TestJwks;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * PostgreSQL is unreachable. A read is a 503 with {@code Retry-After} — and it is answered
 * within the pool's connection timeout, not after Hikari's 30 s default, which held a
 * request thread per read for the whole outage.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = "classpath:api-test.properties",
        properties = {
            "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/statsdb",
            "spring.datasource.username=stats_ro",
            "spring.datasource.password=unused"
        })
class DatabaseUnavailableIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private DataSource dataSource;

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestJwks::jwkSetUri);
    }

    @Test
    @DisplayName("the read pool gives up on a connection after 3 s, not Hikari's 30 s default")
    void connectionTimeoutIsBounded() {
        HikariDataSource pool = (HikariDataSource) dataSource;
        assertThat(pool.getConnectionTimeout()).isEqualTo(3_000L);
        assertThat(pool.getValidationTimeout()).isLessThanOrEqualTo(pool.getConnectionTimeout());
    }

    @Test
    @DisplayName("a read with PostgreSQL down is 503 with Retry-After, answered inside the probe timeout")
    void readIsUnavailableQuickly() throws Exception {
        long started = System.nanoTime();
        MockHttpServletResponse response =
                mvc.perform(MockMvcRequestBuilders.get("/v1/stats/lorastats/CLSCON-db-down")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwks.reader("db-alpha")))
                        .andReturn()
                        .getResponse();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }
}
