package com.zenner.datahub.stats.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.input.ingest.IngestMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Proves Input's operational counters reach the Prometheus surface with their
 * intended label schema.
 *
 * <p>The runtime-path tests separately prove that an unroutable publish, oversized
 * request and rate-limited request increment these counters. This test closes the
 * other silent failure mode: a counter can exist in a {@code MeterRegistry} while no
 * exporter makes it visible outside the JVM.</p>
 */
@SpringBootTest(
        classes = StatsInputApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "STATS_INPUT_AUTH_MODE=none",
            "STATS_INGEST_ROLE=stats.ingest"
        })
@AutoConfigureObservability(tracing = false)
class ManagementSurfaceTest {

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate rest;
    @Autowired private IngestMetrics metrics;

    @Test
    void newlyShippedInputCountersAreActuallyScrapeableWithSafeLabels() {
        metrics.unroutable(StatType.SYSSTATS);
        metrics.requestTooLarge();
        metrics.rateLimited();

        ResponseEntity<String> response =
                rest.getForEntity(
                        "http://localhost:" + port + "/actuator/prometheus", String.class);

        assertThat(response.getStatusCode())
                .as("/actuator/prometheus must export Input's counters")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        String unroutable = sample(response.getBody(), IngestMetrics.UNROUTABLE);
        assertThat(unroutable)
                .isEqualTo("records_unroutable_total{type=\"sysstats\"} 1.0");

        // These filters reject before reading a trusted body. Calling that state
        // type="unknown" would merge transport-level rejection with a record whose
        // type was parsed but invalid.
        assertThat(sample(response.getBody(), IngestMetrics.REQUEST_TOO_LARGE))
                .isEqualTo("requests_too_large_total 1.0");
        assertThat(sample(response.getBody(), IngestMetrics.RATE_LIMITED))
                .isEqualTo("requests_rate_limited_total 1.0");
    }

    private static String sample(String scrape, String metricName) {
        String prometheusName = metricName + "_total";
        return scrape.lines()
                .filter(line -> line.startsWith(prometheusName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(prometheusName + " was not exported"));
    }

    @Test
    void existingRecordCountersExportEverySupportedTypeAndUnknownValidation() {
        for (StatType type : StatType.values()) {
            metrics.accepted(type);
            metrics.validationFailed(type);
            metrics.publishFailed(type);
            metrics.clockSkewRejected(type);
            metrics.converterTimestampPresent(type);
        }
        metrics.validationFailed(null);
        var response = rest.getForEntity("http://localhost:" + port + "/actuator/prometheus", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (StatType type : StatType.values()) {
            for (String name : new String[] {IngestMetrics.ACCEPTED, IngestMetrics.VALIDATION_FAILED,
                    IngestMetrics.PUBLISH_FAILED, IngestMetrics.CLOCK_SKEW_REJECTED, IngestMetrics.CONVERTER_TS_PRESENT}) {
                assertThat(response.getBody()).contains(name + "_total{type=\"" + type.wireName() + "\"} 1.0");
            }
        }
        assertThat(response.getBody()).contains("records_validation_failed_total{type=\"unknown\"} 1.0");
    }
}
