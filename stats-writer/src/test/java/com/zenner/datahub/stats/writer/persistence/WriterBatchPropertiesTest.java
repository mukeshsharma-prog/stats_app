package com.zenner.datahub.stats.writer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WriterBatchPropertiesTest {

    @Test
    void acceptsTheT021IndividualWriteDefaults() {
        WriterBatchProperties properties =
                new WriterBatchProperties(false, 100, Duration.ofMillis(50));

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.maxRecords()).isEqualTo(100);
        assertThat(properties.maxWait()).isEqualTo(Duration.ofMillis(50));
    }

    @Test
    void rejectsUnsupportedBatchingAndInvalidBounds() {
        assertThatThrownBy(
                        () -> new WriterBatchProperties(true, 100, Duration.ofMillis(50)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must remain false");
        assertThatThrownBy(
                        () -> new WriterBatchProperties(false, 0, Duration.ofMillis(50)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
        assertThatThrownBy(
                        () -> new WriterBatchProperties(false, 100, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }
}
