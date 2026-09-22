package com.zenner.datahub.stats.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.input.config.IngestSource;
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
        metrics.requestTooLarge(IngestSource.EXTERNAL);
        metrics.rateLimited(IngestSource.INTERNAL);

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
                .isEqualTo("requests_too_large_total{source=\"external\"} 1.0");
        assertThat(sample(response.getBody(), IngestMetrics.RATE_LIMITED))
                .isEqualTo("requests_rate_limited_total{source=\"internal\"} 1.0");
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
            metrics.accepted(type, IngestSource.INTERNAL);
            metrics.validationFailed(type, IngestSource.INTERNAL);
            metrics.publishFailed(type, IngestSource.INTERNAL);
            metrics.clockSkewRejected(type, IngestSource.INTERNAL);
            metrics.converterTimestampPresent(type, IngestSource.INTERNAL);
        }
        metrics.validationFailed(null, null);
        var response = rest.getForEntity("http://localhost:" + port + "/actuator/prometheus", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (StatType type : StatType.values()) {
            for (String name : new String[] {IngestMetrics.ACCEPTED, IngestMetrics.VALIDATION_FAILED,
                    IngestMetrics.PUBLISH_FAILED, IngestMetrics.CLOCK_SKEW_REJECTED, IngestMetrics.CONVERTER_TS_PRESENT}) {
                // DL-151: every per-type series also carries the ingest door.
                assertThat(response.getBody())
                        .contains(name + "_total{source=\"internal\",type=\"" + type.wireName() + "\"} 1.0");
            }
        }
        assertThat(response.getBody())
                .contains("records_validation_failed_total{source=\"unknown\",type=\"unknown\"} 1.0")
                // T-048 (DL-159): the confirm histogram, by its exported names, before any
                // record has been published -- what the Input dashboard's p99 reads.
                .contains("records_publish_confirm_seconds_bucket{outcome=\"confirmed\",type=\"lorastats\",le=\"0.001\"} 0")
                .contains("records_publish_confirm_seconds_bucket{outcome=\"confirmed\",type=\"omsstats\",le=\"5.0\"} 0")
                .contains("records_publish_confirm_seconds_count{outcome=\"confirmed\",type=\"sysstats\"} 0");
    }
}
