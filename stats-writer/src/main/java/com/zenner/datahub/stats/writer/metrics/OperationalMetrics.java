package com.zenner.datahub.stats.writer.metrics;

import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.writer.log.SafeFailure;
import com.zenner.datahub.stats.writer.retention.RetentionWindow;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Broker observations for each stat type this deployment serves, cached so that a
 * Prometheus scrape performs no external I/O (T-027; STA-27A, NFR-05).
 *
 * <p>Passive queue inspection ({@link AmqpAdmin#getQueueInfo}) gives ready-message depth
 * and consumer count without management-plugin credentials or a new dependency. It is
 * read on a schedule and stored; the gauges read the store. A scrape that queried the
 * broker directly would put the broker on the monitoring path and stall every scrape when
 * it is the broker that is unhealthy — the moment the numbers are wanted most.</p>
 *
 * <p><strong>Why NaN, a success flag and a last-success timestamp rather than a zero.</strong>
 * A queue the broker cannot report on and an empty queue must not read the same. Zero
 * consumers on a healthy broker is an outage; a gauge that reported zero because the last
 * poll threw would be an outage that looks like a quiet queue. So a failed observation
 * writes NaN, drops the success flag and leaves the last-success time where it was, and
 * an alert has to check freshness before treating any zero as healthy.</p>
 *
 * <p><strong>What is deliberately not here.</strong> Retention health is reported by the
 * sweep itself as {@code retention_oldest_partition_age_days} (DL-132), which rejected a
 * polled catalogue gauge on purpose: it would keep reporting a healthy number from a
 * replica whose sweep never ran. Only the configured window is exported from here, and it
 * is read from {@link RetentionWindow} — the one definition the guard and the sweep share
 * (DL-133) — so this class cannot advertise a window different from the one in force.
 * Main-queue depth is a diagnostic, not the oldest-message-age alert T-050 requires:
 * under jitter (DL-104) a cyclic depth spike is evidence, not a buffer working. That
 * alert reads {@link QueueAgeMetrics}, which is sampled at delivery; this class registers
 * its gauge for every served type and resets it whenever a poll finds the queue empty
 * (DL-147).</p>
 */
@Component
public final class OperationalMetrics {

    public static final String QUEUE_DEPTH = "queue_depth";
    public static final String DLQ_DEPTH = "dlq_depth";
    public static final String CONSUMER_AVAILABILITY = "consumer_availability";
    public static final String COLLECTION_SUCCESS = "broker_metrics_collection_success";
    public static final String LAST_SUCCESS = "broker_metrics_last_success_timestamp_seconds";
    public static final String RETENTION_PERIOD_DAYS = "retention_period_days";

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationalMetrics.class);

    private final AmqpAdmin admin;
    private final QueueAgeMetrics queueAge;
    private final Map<StatType, AtomicReference<BrokerSample>> broker =
            new EnumMap<>(StatType.class);

    public OperationalMetrics(
            AmqpAdmin admin,
            RetentionWindow retention,
            MeterRegistry registry,
            QueueAgeMetrics queueAge,
            List<Queue> queues,
            @Value("${stats.writer.stat-type:all}") String selectedType) {
        this.admin = Objects.requireNonNull(admin, "admin");
        this.queueAge = Objects.requireNonNull(queueAge, "queueAge");
        Objects.requireNonNull(retention, "retention");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(queues, "queues");
        Objects.requireNonNull(selectedType, "selectedType");

        for (StatType type : StatType.values()) {
            // In production one deployment serves one type (ADR-003 D8). In development
            // `all` mode the served set is whatever queues this context declared, so a
            // type whose consumer has not been built yet gets no gauges rather than NaN
            // forever.
            boolean served =
                    "all".equals(selectedType)
                            ? queues.stream()
                                    .anyMatch(queue -> queue.getName().equals(AmqpTopology.mainQueue(type)))
                            : type == StatType.fromWireName(selectedType);
            if (!served) {
                continue;
            }
            AtomicReference<BrokerSample> sample = new AtomicReference<>(BrokerSample.UNKNOWN);
            broker.put(type, sample);
            queueAge.register(type);
            gauge(registry, QUEUE_DEPTH,
                    "Ready messages on the main queue; excludes unacknowledged deliveries",
                    type, sample, BrokerSample::ready);
            gauge(registry, DLQ_DEPTH, "Ready messages on the dead-letter queue",
                    type, sample, BrokerSample::dlq);
            gauge(registry, CONSUMER_AVAILABILITY,
                    "Broker-reported active consumers on the main queue, across replicas",
                    type, sample, BrokerSample::consumers);
            gauge(registry, COLLECTION_SUCCESS,
                    "One if the latest broker observation succeeded", type, sample,
                    BrokerSample::success);
            gauge(registry, LAST_SUCCESS,
                    "Unix time of the last successful broker observation; zero until one has",
                    type, sample, BrokerSample::lastSuccess);
            Gauge.builder(RETENTION_PERIOD_DAYS, retention, window -> window.retentionDaysFor(type))
                    .description("Configured ts.device retention window, as the sweep and guard apply it")
                    .tag("type", type.wireName())
                    .register(registry);
        }
    }

    private static void gauge(
            MeterRegistry registry,
            String name,
            String description,
            StatType type,
            AtomicReference<BrokerSample> state,
            ToDoubleFunction<BrokerSample> value) {
        Gauge.builder(name, state, reference -> value.applyAsDouble(reference.get()))
                .description(description)
                .tag("type", type.wireName())
                .register(registry);
    }

    /** One passive inspection per served type. Never throws: a broker fault is a sample. */
    @Scheduled(
            fixedDelayString = "${stats.metrics.poll-interval:PT30S}",
            initialDelayString = "${stats.metrics.initial-delay:PT30S}")
    public void poll() {
        for (Map.Entry<StatType, AtomicReference<BrokerSample>> entry : broker.entrySet()) {
            pollBroker(entry.getKey(), entry.getValue());
        }
    }

    private void pollBroker(StatType type, AtomicReference<BrokerSample> sample) {
        try {
            QueueInformation main = admin.getQueueInfo(AmqpTopology.mainQueue(type));
            QueueInformation dlq = admin.getQueueInfo(AmqpTopology.deadLetterQueue(type));
            if (main == null || dlq == null) {
                throw new IllegalStateException("Expected queue is unavailable");
            }
            sample.set(
                    new BrokerSample(
                            main.getMessageCount(),
                            dlq.getMessageCount(),
                            main.getConsumerCount(),
                            1,
                            Instant.now().getEpochSecond()));
            if (main.getMessageCount() == 0) {
                // No ready message means no oldest message. Without this the age gauge
                // would keep the last backlog value through a quiet hour (DL-147).
                queueAge.observedEmpty(type);
            }
        } catch (RuntimeException failure) {
            sample.set(BrokerSample.failed(sample.get()));
            // The cause is described, never passed (guard P2): an AMQP exception carries
            // a host and port, and the shape here is the one the next site copies.
            LOGGER.atWarn()
                    .addKeyValue("event", "metrics_collection_failed")
                    .addKeyValue("errorCode", "metrics_collection_failed")
                    .addKeyValue("statType", type.wireName())
                    .addKeyValue("cause", SafeFailure.describe(failure))
                    .log(
                            "Broker observation unavailable; queue and consumer gauges are NaN "
                                    + "until collection recovers");
        }
    }

    private record BrokerSample(
            double ready, double dlq, double consumers, double success, double lastSuccess) {

        static final BrokerSample UNKNOWN =
                new BrokerSample(Double.NaN, Double.NaN, Double.NaN, 0, 0);

        /** A failed poll keeps the previous last-success time, so staleness is measurable. */
        static BrokerSample failed(BrokerSample previous) {
            return new BrokerSample(Double.NaN, Double.NaN, Double.NaN, 0, previous.lastSuccess());
        }
    }
}
