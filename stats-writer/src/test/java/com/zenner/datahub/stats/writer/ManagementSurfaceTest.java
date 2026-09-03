package com.zenner.datahub.stats.writer;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.testsupport.PostgresSupport;
import com.zenner.datahub.stats.testsupport.RabbitSupport;
import com.zenner.datahub.stats.writer.persistence.LorastatsBatchWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Proves the Writer's counters are reachable from OUTSIDE the JVM, and that the
 * management surface is the only thing this module serves.
 *
 * <p>This is a regression guard for a failure with no symptom. {@code
 * spring-boot-starter-actuator} brings {@code micrometer-core}, which is enough to
 * register a {@link io.micrometer.core.instrument.Counter} and not enough to export
 * one; with no registry implementation on the runtime classpath Spring Boot falls
 * back to {@code SimpleMeterRegistry} and every counter stays inside the process.
 * Nothing fails: the module starts, the writes commit, the unit tests that assert
 * {@code counter.count()} still pass, and the metric simply never arrives at a
 * dashboard. The same was true one level up — this module had no servlet container
 * at all, so {@code server.port} and {@code management.endpoints.web.exposure.include}
 * were inert and docker-compose's readiness healthcheck on 8082 could never
 * pass.</p>
 *
 * <p>It matters most for {@code records_duplicate_suppressed}. STA-21B/STA-27A make
 * duplicate suppression deliberately silent at the database — {@code ON CONFLICT DO
 * NOTHING} — so this counter is the ONLY thing that distinguishes a suppressed
 * duplicate from a write that never happened, and a cross-tenant {@code uid}
 * collision is visible nowhere else (ADR-003, ADR-005). An unexported counter turns
 * that back into silence.</p>
 *
 * <p>A registered Micrometer counter is scraped at 0.0 before it is ever
 * incremented, so asserting on the name alone is enough and this test needs no
 * partition, no message, and no write. (T-022, T-023, T-058, T-027; DL-100)</p>
 */
@SpringBootTest(
        classes = StatsWriterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // Deploy as a type with no consumer. Spring caches this context for the
            // rest of the JVM, so leaving stats.writer.stat-type at its `all` default
            // would keep a live lorastats listener attached to the SHARED broker
            // container and steal the deliveries LorastatsConsumerIntegrationTest
            // asserts on. LorastatsBatchWriter is unconditional, so the counters this
            // test scrapes are registered either way.
            "stats.writer.stat-type=netstats",
            "stats.retention.schedule=-",
            "stats.retention.pre-create-on-startup=false"
        })
// Spring Boot switches metrics EXPORT off under @SpringBootTest — its context
// customizer injects management.defaults.metrics.export.enabled=false so ordinary
// tests do not stand up exporters. Without this annotation the scrape below 404s in
// a correctly configured application, which would make this guard assert the
// opposite of what it exists for. Tracing stays off; nothing here uses it.
@AutoConfigureObservability(tracing = false)
@Import({PostgresSupport.class, RabbitSupport.class})
class ManagementSurfaceTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    // Actuator contributes its own RequestMappingHandlerMapping for controller
    // endpoints, so this must name the MVC one. Autowiring by type alone matches
    // both and the assertion below would be about the wrong mapping.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyWriterCounterIsActuallyScrapeable() {
        ResponseEntity<String> scrape =
                rest.getForEntity("http://localhost:" + port + "/actuator/prometheus", String.class);

        assertThat(scrape.getStatusCode())
                .as("/actuator/prometheus is missing: no registry implementation, or no web server")
                .isEqualTo(HttpStatus.OK);

        // Micrometer appends _total to a counter in Prometheus' naming convention,
        // and the type tag is what keeps the metric per-type (rule 6).
        assertThat(scrape.getBody())
                .contains(LorastatsBatchWriter.DUPLICATE_METRIC + "_total")
                .contains(LorastatsBatchWriter.UNMAPPED_DATA_METRIC + "_total")
                .contains(LorastatsBatchWriter.WRITE_FAILED_METRIC + "_total")
                .contains("type=\"lorastats\"");
    }

    @Test
    void readinessIsServedOnThePortDockerComposeHealthchecks() {
        // docker/docker-compose.yml health-checks /actuator/health/readiness. D13
        // wants readiness to probe this module's REAL dependencies, so the container
        // beans being up is what makes this 200 rather than a hard-coded UP.
        ResponseEntity<String> readiness =
                rest.getForEntity(
                        "http://localhost:" + port + "/actuator/health/readiness", String.class);

        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getBody()).contains("UP");
    }

    /**
     * The servlet container is here for actuator, not to open an application
     * endpoint. Rule 1 puts every GET in stats-api and rule 2 makes this the only
     * module with write access to PostgreSQL; a controller here would be a write path
     * into the database reachable over HTTP, which is exactly what neither rule
     * permits.
     */
    @Test
    void theWebServerCarriesNoApplicationEndpoint() {
        // Boot's own BasicErrorController is framework furniture, not an endpoint
        // this module declares; it renders the response for requests reaching nothing.
        assertThat(handlerMapping.getHandlerMethods().values())
                .as("no stats-writer controller may be mapped")
                .allMatch(
                        handler ->
                                handler.getBeanType()
                                        .getName()
                                        .startsWith("org.springframework.boot."));
    }
}
