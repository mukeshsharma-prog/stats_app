package com.zenner.datahub.stats.input.publish;

import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Input's publish settings — {@code stats.amqp} (T-014, STA-04A).
 *
 * <p><strong>exchange is bound and then checked against the constant.</strong> The
 * name lived in {@code application.properties} for weeks with nothing reading it
 * (DL-063 called that out), and an unread property is worse than none: it looks
 * configured. Binding it without the check would be worse still — a deployment could
 * point Input at an exchange no Writer binds a queue to, and every record would be
 * accepted, confirmed by the broker and dropped on the floor. So the property exists
 * for visibility and may only hold the one value {@link AmqpTopology} prescribes;
 * anything else refuses to start (ADR-002 §11, the DL-101 pattern).</p>
 *
 * <p><strong>confirmTimeout is a bound, not a tuning knob.</strong> It is how long a
 * Heimdal request may block waiting for the broker. Unbounded is not an option: the
 * caller would hold a connection until its own client timeout fires, and Input would
 * have no idea whether the record was published. Expiry is answered 503, which is
 * honest — we do not know, so Heimdal must resend.</p>
 */
@ConfigurationProperties(prefix = "stats.amqp")
public record AmqpPublisherProperties(String exchange, Duration confirmTimeout) {

    public AmqpPublisherProperties {
        Objects.requireNonNull(exchange, "stats.amqp.exchange");
        Objects.requireNonNull(confirmTimeout, "stats.amqp.confirm-timeout");

        if (!AmqpTopology.INPUT_EXCHANGE.equals(exchange)) {
            throw new IllegalArgumentException(
                    "stats.amqp.exchange must be '" + AmqpTopology.INPUT_EXCHANGE
                            + "' — the topology name is owned by stats-common, not by a "
                            + "deployment (ADR-002 §11)");
        }
        if (confirmTimeout.isZero() || confirmTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "stats.amqp.confirm-timeout must be positive — a 2xx follows a real "
                            + "publisher confirm (STA-04A, rule 8)");
        }
    }
}
