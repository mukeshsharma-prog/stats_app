package com.zenner.datahub.stats.writer.retention;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Creates the partitions this deployment needs <em>before</em> it starts consuming.
 *
 * <p>Partitions must be pre-created ahead of ingestion, or inserts fail.</p>
 *
 * <p><strong>Why this exists separately from the cron.</strong> The scheduled run
 * ({@code stats.retention.schedule}, default 03:30 UTC) is the only other trigger.
 * A module deployed at any other hour would therefore come up against a parent
 * table with no partitions and stay that way for up to a day: every insert fails
 * with {@code no partition of relation "lorastats" found for row}, the
 * manual-ACK contract correctly withholds the ACK, and the consumer wedges once it
 * has held its whole prefetch window unacknowledged. Nothing is lost — the broker
 * keeps every record — but ingestion for the type stops, and it does not recover on
 * its own when the cron eventually fires, because those deliveries stay unacked
 * until the container restarts.</p>
 *
 * <p><strong>Why {@link SmartLifecycle} and not {@code @PostConstruct} or
 * {@code ApplicationReadyEvent}.</strong> It has to land in the window after Flyway
 * has migrated and before the AMQP listener containers start, and only the
 * lifecycle phases give that ordering as a guarantee rather than a coincidence:</p>
 *
 * <ul>
 *   <li>{@code @PostConstruct} runs during bean creation, which is not ordered
 *       against the Flyway initializer unless this class takes a bean-name
 *       {@code @DependsOn} on it — brittle, and wrong the moment Flyway is disabled.</li>
 *   <li>{@code ApplicationReadyEvent} is published <em>after</em>
 *       {@code finishRefresh()}, and {@code finishRefresh()} is exactly where the
 *       listener containers start. The consumer would already be pulling messages.</li>
 *   <li>{@code SmartLifecycle#start()} runs inside {@code finishRefresh()}, after
 *       every bean — Flyway included — is fully initialised, and ordered by
 *       {@link #PHASE}. Spring AMQP's containers sit near {@code Integer.MAX_VALUE},
 *       so a low phase here starts first. {@code PartitionBootstrapPhaseTest} asserts
 *       that relationship against the real container rather than trusting it.</li>
 * </ul>
 *
 * <p>Can be turned off with {@code stats.retention.pre-create-on-startup=false} for
 * deployments that run partition DDL out of band — for example where the writer's
 * database role is deliberately not allowed to issue DDL and a separate migration
 * job owns it. The scheduled run still applies. Turning it off in a deployment that
 * has no such job reopens the gap this class closes.</p>
 *
 * <p>A failure here is deliberately fatal: the context fails to start, the process
 * exits non-zero, and the orchestrator restarts or holds the rollout. That is the
 * loud failure this needs. The alternative — start anyway — is the silent
 * wedge described above.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "stats.retention",
        name = "pre-create-on-startup",
        havingValue = "true",
        matchIfMissing = true)
public class PartitionBootstrap implements SmartLifecycle {

    /**
     * Well below Spring AMQP's listener-container phase, so partitions exist before
     * the first delivery. Asserted against the real container in the phase test.
     */
    static final int PHASE = 0;

    private static final Logger LOGGER = LoggerFactory.getLogger(PartitionBootstrap.class);

    private final PartitionMaintenance maintenance;

    private volatile boolean running;

    public PartitionBootstrap(PartitionMaintenance maintenance) {
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
    }

    @Override
    public void start() {
        LOGGER.atInfo()
                .addKeyValue("event", "partition_bootstrap_started")
                .log("Ensuring partitions before ingestion starts");
        maintenance.ensureBeforeIngestion();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
