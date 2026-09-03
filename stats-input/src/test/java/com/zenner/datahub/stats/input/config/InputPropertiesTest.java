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
        assertThat(new IngestEndpointProperties("/stats", 200).successStatus()).isEqualTo(200);
        assertThat(new IngestEndpointProperties("/stats", 202).successStatus()).isEqualTo(202);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IngestEndpointProperties("/stats", 204))
                .withMessageContaining("200 or 202");
    }

    @Test
    @DisplayName("a relative endpoint path refuses to start")
    void rejectsRelativePath() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IngestEndpointProperties("stats", 200));
    }
}
