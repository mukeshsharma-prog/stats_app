package com.zenner.datahub.stats.writer;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.testsupport.PostgresSupport;
import com.zenner.datahub.stats.testsupport.RabbitSupport;
import com.zenner.datahub.stats.writer.persistence.LorastatsBatchWriter;
import com.zenner.datahub.stats.writer.retry.WriterFailureMetrics;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
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
 * <p>The persistence counters are registered when their writer bean is created.
 * Retry and DLQ counters are dynamic because their tier and reason labels exist only
 * when a failure is routed, so this test exercises that metric component before the
 * scrape. It still needs no partition, broker message, or database write: dedicated
 * routing tests prove when the component is called.</p>
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
            "stats.retention.pre-create-on-startup=false",
            // Health details are hidden by default, which is the right production
            // setting — an unauthenticated probe should not enumerate dependencies.
            // These two make the group's MEMBERSHIP observable to the assertions
            // below; they change what is rendered, never what the group contains.
            "management.endpoint.health.group.readiness.show-components=always",
            "management.endpoint.health.group.liveness.show-components=always"
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

    @Autowired private WriterFailureMetrics failureMetrics;

    @Test
    void everyWriterCounterIsActuallyScrapeable() {
        // Retry and DLQ meters are created on first use because their tier/reason
        // labels are known only when routing a failure. Exercise the metric component
        // here; FailedDeliveryRouterTest separately proves the real routing paths call
        // it only after a confirmed hand-off.
        failureMetrics.retried(StatType.LORASTATS, 2);
        failureMetrics.deadLettered(StatType.LORASTATS, "DataIntegrityViolationException");

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
                .contains("records_persisted_total{type=\"lorastats\"}")
                .contains("records_postgres_errors_total{type=\"lorastats\"}")
                .contains("type=\"lorastats\"");

        String retried = sample(scrape.getBody(), WriterFailureMetrics.RETRIED);
        assertThat(retried)
                .contains("type=\"lorastats\"")
                .contains("tier=\"2\"")
                .doesNotContain("uid=", "tenant=", "clsbox=")
                .endsWith(" 1.0");

        String deadLettered = sample(scrape.getBody(), WriterFailureMetrics.DEAD_LETTERED);
        assertThat(deadLettered)
                .contains("type=\"lorastats\"")
                .contains("reason=\"DataIntegrityViolationException\"")
                .doesNotContain("uid=", "tenant=", "clsbox=")
                .endsWith(" 1.0");
    }

    private static String sample(String scrape, String metricName) {
        String prometheusName = metricName + "_total";
        return scrape.lines()
                .filter(line -> line.startsWith(prometheusName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(prometheusName + " was not exported"));
    }

    @Autowired private io.micrometer.core.instrument.MeterRegistry registry;
    @Autowired private com.zenner.datahub.stats.writer.metrics.QueueAgeMetrics queueAge;

    @Test
    void deliveryAttemptMetricsReachTheScrapeEvenForAMalformedBody() throws Exception {
        // The context deliberately has no live consumer, to avoid stealing other
        // integration tests' broker messages. Drive the real listener with mocked ports.
        var processor = org.mockito.Mockito.mock(
                com.zenner.datahub.stats.writer.consumer.StatsMessageProcessor.class);
        org.mockito.Mockito.when(processor.statType()).thenReturn(StatType.LORASTATS);
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("processor", processor);
        var availability = new com.zenner.datahub.stats.writer.consumer.StatsMessageProcessorAvailability(
                beans.getBeanProvider(com.zenner.datahub.stats.writer.consumer.StatsMessageProcessor.class));
        var router = org.mockito.Mockito.mock(
                com.zenner.datahub.stats.writer.retry.FailedDeliveryRouter.class);
        var message = new org.springframework.amqp.core.Message(new byte[] {'{'}, new org.springframework.amqp.core.MessageProperties());
        org.mockito.Mockito.doThrow(new IllegalArgumentException("malformed body"))
                .when(processor).process(StatType.LORASTATS, message);
        var consumer = new com.zenner.datahub.stats.writer.consumer.LorastatsConsumer(availability, router, registry, queueAge);
        consumer.consume(message, org.mockito.Mockito.mock(com.rabbitmq.client.Channel.class));
        var scrape = rest.getForEntity("http://localhost:" + port + "/actuator/prometheus", String.class);
        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(scrape.getBody())
                .contains("records_consumed_total{type=\"lorastats\"} 1.0")
                .contains("records_processing_duration_seconds_count{type=\"lorastats\"} 1")
                .contains("records_processing_duration_seconds_sum{type=\"lorastats\"}");
    }

    /**
     * T-050 / DL-147: the oldest-message-age gauge reaches the scrape under the type
     * this deployment serves, and NaN — "not observed yet" — is what it exports until a
     * delivery or a poll has said otherwise. A NaN that Prometheus ingests is a sample
     * the alert rules can tell apart from zero; an absent series is not.
     */
    @Test
    void theOldestMessageAgeGaugeReachesTheScrape() {
        var scrape = rest.getForEntity("http://localhost:" + port + "/actuator/prometheus", String.class);

        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
        String sample = scrape.getBody().lines()
                .filter(line -> line.startsWith(
                        com.zenner.datahub.stats.writer.metrics.QueueAgeMetrics.OLDEST_MESSAGE_AGE
                                + "{type=\"netstats\"}"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("queue_oldest_message_age_seconds was not exported"));
        assertThat(sample).doesNotContain("uid=", "tenant=", "clsbox=");
    }

    @Test
    void readinessIsServedOnThePortDockerComposeHealthchecks() {
        // docker/docker-compose.yml health-checks /actuator/health/readiness, and until
        // this module had a servlet container that endpoint did not exist at all.
        ResponseEntity<String> readiness =
                rest.getForEntity(
                        "http://localhost:" + port + "/actuator/health/readiness", String.class);

        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getBody()).contains("UP");
    }

    /**
     * D13: readiness probes this module's <em>real</em> dependencies. For the Writer
     * that is PostgreSQL <em>and</em> RabbitMQ — it consumes from one and commits to
     * the other, and cannot do its job with either gone.
     *
     * <p>This guards a default that fails <strong>open</strong>. Boot's out-of-the-box
     * readiness group is {@code readinessState} alone, so before
     * {@code management.endpoint.health.group.readiness.include} was set this endpoint
     * returned UP with both dependencies down — and compose health-checks exactly this
     * URL, so a Writer that could not write would have been reported healthy and, in an
     * orchestrator, had traffic routed to it. Deleting that property is a silent
     * regression; this test is what makes it loud.</p>
     */
    @Test
    void readinessProbesPostgresAndTheBrokerRatherThanJustTheProcess() {
        ResponseEntity<Map<String, Object>> readiness =
                rest.exchange(
                        "http://localhost:" + port + "/actuator/health/readiness",
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getBody()).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> components =
                (Map<String, Object>) readiness.getBody().get("components");

        assertThat(components)
                .as("readiness must name every real dependency, not just readinessState")
                .containsKeys("readinessState", "db", "rabbit");
    }

    /**
     * The other half of D13, and the reason the two groups are configured as a pair.
     *
     * <p>Liveness answers "should this process be restarted". A dependency in this
     * group turns a database blip into a restart loop that cannot fix itself: every
     * replica is killed, and each replacement finds the same database still down.</p>
     */
    @Test
    void livenessProbesTheProcessOnlySoADatabaseBlipCannotRestartEveryReplica() {
        ResponseEntity<Map<String, Object>> liveness =
                rest.exchange(
                        "http://localhost:" + port + "/actuator/health/liveness",
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(liveness.getBody()).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> components =
                (Map<String, Object>) liveness.getBody().get("components");

        assertThat(components).containsKey("livenessState");
        assertThat(components)
                .as("a dependency in the liveness group is a restart loop, not a health check")
                .doesNotContainKeys("db", "rabbit");
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
