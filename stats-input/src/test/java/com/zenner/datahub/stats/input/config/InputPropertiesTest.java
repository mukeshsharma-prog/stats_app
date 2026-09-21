package com.zenner.datahub.stats.input.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The two startup guards that stop a quiet edit changing decided behaviour. */
class InputPropertiesTest {

    @Test
    @DisplayName("the envelope scope binds")
    void acceptsEnvelopeScope() {
        InputValidationProperties properties =
                new InputValidationProperties("envelope", Duration.ofMinutes(5));
        assertThat(properties.maxClockSkew()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("any other validation scope refuses to start — ADR-007 §5")
    void rejectsAWiderScope() {
        // Setting this to `payload` would make Input 400 a dialect disagreement, which
        // Heimdal never retries. The failure has to be at startup, not in production.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new InputValidationProperties("payload", Duration.ofMinutes(5)))
                .withMessageContaining("envelope");
    }

    @Test
    @DisplayName("a negative clock-skew tolerance refuses to start")
    void rejectsNegativeSkew() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new InputValidationProperties("envelope", Duration.ofMinutes(-1)));
    }

    @Test
    @DisplayName("the success status is constrained to the two the contract admits")
    void constrainsSuccessStatus() {
        assertThat(endpoint("/internal/stats", "/external/stats", 200).successStatus()).isEqualTo(200);
        assertThat(endpoint("/internal/stats", "/external/stats", 202).successStatus()).isEqualTo(202);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> endpoint("/internal/stats", "/external/stats", 204))
                .withMessageContaining("200 or 202");
    }

    @Test
    @DisplayName("a relative endpoint path refuses to start — either door")
    void rejectsRelativePath() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> endpoint("internal/stats", "/external/stats", 200))
                .withMessageContaining("internal-path");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> endpoint("/internal/stats", "external/stats", 200))
                .withMessageContaining("external-path");
    }

    /**
     * DL-151: the split exists to tell the two doors apart. One path under two names
     * would register every ingest filter twice and make the {@code source} label
     * meaningless, so it is refused at startup rather than discovered on a dashboard.
     */
    @Test
    @DisplayName("the two ingest paths must differ")
    void rejectsIdenticalPaths() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> endpoint("/stats", "/stats", 200))
                .withMessageContaining("must differ");
    }

    /**
     * The ingest filters — size, rate, authentication — are registered on these paths.
     * A door under {@code /actuator} would put the readiness probe behind them (D13).
     */
    @Test
    @DisplayName("an ingest path under /actuator refuses to start")
    void rejectsActuatorPath() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> endpoint("/actuator/stats", "/external/stats", 200))
                .withMessageContaining("/actuator");
    }

    @Test
    @DisplayName("each door resolves to its source, anything else to null")
    void resolvesSourceFromPath() {
        IngestEndpointProperties endpoint = endpoint("/internal/stats", "/external/stats", 200);

        assertThat(endpoint.sourceOf("/internal/stats")).isEqualTo(IngestSource.INTERNAL);
        assertThat(endpoint.sourceOf("/external/stats")).isEqualTo(IngestSource.EXTERNAL);
        assertThat(endpoint.sourceOf("/actuator/health")).isNull();
        assertThat(endpoint.paths()).containsExactly("/internal/stats", "/external/stats");
        assertThat(endpoint.pathOf(IngestSource.EXTERNAL)).isEqualTo("/external/stats");
    }

    private static IngestEndpointProperties endpoint(String internal, String external, int status) {
        return new IngestEndpointProperties(internal, external, status);
    }
}
