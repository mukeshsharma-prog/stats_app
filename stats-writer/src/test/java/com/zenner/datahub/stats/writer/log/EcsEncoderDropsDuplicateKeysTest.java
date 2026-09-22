package com.zenner.datahub.stats.writer.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;

/**
 * Characterises the failure DL-150 closes, against the encoder production actually
 * runs: {@code logging.structured.format.console=ecs}.
 *
 * <p>This is the executable reason for {@code LogsDoNotShadowMdcKeysTest}. A unit
 * test with a plain test logger cannot see the problem — the statement "works" — and a
 * rule whose justification lives only in a javadoc is one Boot upgrade away from being
 * either unnecessary or insufficient without anyone noticing. If a future Boot release
 * merges or overrides duplicate members instead of throwing, the first test here fails
 * and the guard can be reconsidered; until then it stands.</p>
 */
class EcsEncoderDropsDuplicateKeysTest {

    private final LoggerContext context = new LoggerContext();
    private final StructuredLogEncoder encoder = new StructuredLogEncoder();

    @BeforeEach
    void startEcsEncoder() {
        // StructuredLogEncoder resolves its format through the Spring Environment it
        // expects to find in the logger context, exactly as LogbackLoggingSystem wires it.
        context.putObject(Environment.class.getName(), new StandardEnvironment());
        encoder.setContext(context);
        encoder.setFormat("ecs");
        encoder.start();
    }

    @AfterEach
    void stopEncoder() {
        encoder.stop();
        context.stop();
    }

    /** The mechanism: the same key from the MDC and from a key-value pair is fatal. */
    @Test
    void aKeyValuePairThatRepeatsAnMdcKeyMakesTheEncoderThrow() {
        LoggingEvent event = event(Map.of("uid", "uid-from-scope"), List.of(new KeyValuePair("uid", "uid-from-scope")));

        assertThatThrownBy(() -> encoder.encode(event))
                .as("logback catches this inside the appender and DROPS the line")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate nested pairs added under 'uid'");
    }

    /** The sanctioned shape: MDC keys once, statement-specific keys beside them. */
    @Test
    void mdcKeysAndDistinctKeyValuePairsEncodeOnOneLine() {
        LoggingEvent event =
                event(
                        Map.of("uid", "uid-from-scope", "statType", "lorastats"),
                        List.of(new KeyValuePair("event", "expired_on_arrival"), new KeyValuePair("ageDays", 95)));

        String line = new String(encoder.encode(event), StandardCharsets.UTF_8);

        assertThat(line)
                .contains("\"uid\":\"uid-from-scope\"")
                .contains("\"statType\":\"lorastats\"")
                .contains("\"event\":\"expired_on_arrival\"")
                .contains("\"ageDays\":95");
    }

    private LoggingEvent event(Map<String, String> mdc, List<KeyValuePair> pairs) {
        LoggingEvent event = new LoggingEvent();
        event.setLoggerContext(context);
        event.setLoggerName("com.zenner.datahub.stats.writer.Characterisation");
        event.setLevel(Level.WARN);
        event.setMessage("characterisation");
        event.setTimeStamp(0L);
        event.setMDCPropertyMap(mdc);
        event.setKeyValuePairs(pairs);
        return event;
    }
}
