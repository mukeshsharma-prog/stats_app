package com.zenner.datahub.stats.writer.metrics;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.writer.retry.FailedDeliveryRouter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Component;

/**
 * The oldest-message age of each served main queue — the series T-050 alerts on, and the
 * one ADR-002 §12 and Q13a ask for in place of depth (DL-147).
 *
 * <h2>How it is measured</h2>
 *
 * <p>Input stamps the standard AMQP {@code timestamp} property on every message it
 * publishes (DL-147). {@code app_statsstore.<type>} is FIFO, so the delivery the Writer
 * is about to process <em>was</em> the oldest ready message at the moment the broker
 * handed it over. Its age at listener entry — receive time minus publish time — is
 * therefore the oldest-message age of the queue, sampled once per delivery. When the
 * {@link OperationalMetrics} poll finds the queue empty the gauge is set to zero: an
 * empty queue has no oldest message, and without that reset the gauge would hold the
 * last backlog value for as long as traffic stayed quiet.</p>
 *
 * <h2>What is deliberately excluded</h2>
 *
 * <p>A delivery carrying {@value FailedDeliveryRouter#ATTEMPT_HEADER} has come back
 * from a retry tier. Its age is the tier's delay by design — thirty seconds, five
 * minutes, thirty minutes — and counting it would make every transient database
 * failure look like the Writer falling behind. The retry arm has its own counters
 * ({@code records_retried}, {@code records_write_failed}); this gauge answers only
 * "how long does a fresh record wait before the Writer takes it". A delivery with no
 * timestamp at all — published by something other than Input, or by an Input built
 * before DL-147 — is skipped rather than guessed at.</p>
 *
 * <h2>The blind spot, and what covers it</h2>
 *
 * <p>A gauge sampled at delivery stops moving when deliveries stop. If the consumer is
 * absent or wedged while messages wait, the last observed age stays on the scrape and
 * reads as healthy. That is the same objection DL-137 raised against a polled
 * retention gauge, and it is not solved here by inventing a value: it is covered by two
 * companion rules in {@code ops/prometheus/writer-alerts.yml} that read what the poll
 * <em>does</em> know — {@code consumer_availability == 0} while depth is non-zero, and
 * depth non-zero with no {@code records_consumed} increase for ten minutes. Read the
 * three together; none of them is the alert on its own.</p>
 *
 * <p>Why not read the head message from the broker instead: passive queue inspection
 * (DL-137) reports counts, not messages, and the management API's
 * {@code head_message_timestamp} needs a management credential and an HTTP client in
 * the Writer, which DL-137 chose not to take on. The stamp at Input is the half of that
 * design that costs nothing and is needed either way; a broker-side reader can be added
 * later without changing what is published, and the DLQ would then get its age from the
 * same stamp (DL-144).</p>
 *
 * <p>The clamp at zero absorbs clock skew between the Input and Writer hosts: a message
 * "from the future" is a clock problem, not a negative backlog. AMQP timestamps are
 * whole seconds, so the resolution is one second — plenty for a five-minute threshold.</p>
 */
@Component
public final class QueueAgeMetrics {

    public static final String OLDEST_MESSAGE_AGE = "queue_oldest_message_age_seconds";

    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<StatType, AtomicReference<Double>> ages =
            new ConcurrentHashMap<>();

    public QueueAgeMetrics(MeterRegistry registry, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Registers the gauge for a served type so it exports from startup. NaN until the
     * first observation — "not observed yet" and "empty" must not read the same, for the
     * reason DL-137 gives.
     */
    public void register(StatType type) {
        state(Objects.requireNonNull(type, "type"));
    }

    /**
     * Samples the age of one delivery at listener entry. Returns the age observed, or
     * NaN when the delivery was not eligible (no timestamp, or a retry re-entry).
     */
    public double observe(StatType type, Message message) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(message, "message");
        MessageProperties properties = message.getMessageProperties();
        if (properties == null) {
            return Double.NaN;
        }
        Date published = properties.getTimestamp();
        if (published == null || isRetryReentry(properties)) {
            return Double.NaN;
        }
        double age =
                Math.max(0.0d, (clock.millis() - published.getTime()) / 1000.0d);
        state(type).set(age);
        return age;
    }

    /** The poll found no ready message: nothing is waiting, so nothing is aging. */
    public void observedEmpty(StatType type) {
        state(Objects.requireNonNull(type, "type")).set(0.0d);
    }

    private static boolean isRetryReentry(MessageProperties properties) {
        Map<String, Object> headers = properties.getHeaders();
        if (headers == null) {
            return false;
        }
        // A redriven record carries the attempt header too (DL-138); the second check
        // is belt to that braces, so a redrive can never read as a backlog.
        return headers.containsKey(FailedDeliveryRouter.ATTEMPT_HEADER)
                || headers.containsKey(FailedDeliveryRouter.MANUAL_REDRIVE_HEADER);
    }

    private AtomicReference<Double> state(StatType type) {
        return ages.computeIfAbsent(
                type,
                key -> {
                    AtomicReference<Double> reference = new AtomicReference<>(Double.NaN);
                    Gauge.builder(OLDEST_MESSAGE_AGE, reference, AtomicReference::get)
                            .description(
                                    "Age of the oldest ready message on the main queue, "
                                            + "sampled at delivery from Input's publish timestamp; "
                                            + "zero when the last poll found the queue empty")
                            .baseUnit("seconds")
                            .tag("type", key.wireName())
                            .register(registry);
                    return reference;
                });
    }
}
