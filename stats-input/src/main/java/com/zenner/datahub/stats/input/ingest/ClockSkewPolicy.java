package com.zenner.datahub.stats.input.ingest;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The Q14a clock-skew rule (ADR-003 D9, closes Q14a), as a pure function.
 *
 * <p>A record is rejected when {@code ts.device} runs more than the configured
 * tolerance <em>ahead</em> of {@code ts.server}. It is a one-sided check on purpose,
 * and the asymmetry is the whole design:</p>
 *
 * <ul>
 *   <li><strong>Ahead is rejected.</strong> Partition pre-creation is finite, so a
 *       gateway claiming 2038 either demands unbounded partitions or fails deep in
 *       the Writer, where the caller can no longer be told anything.</li>
 *   <li><strong>Behind is accepted, however far behind.</strong> A gateway that was
 *       offline for weeks reports a genuinely old {@code ts.device}, and retention
 *       keys on that field (STA-23B). Rejecting the past would discard exactly the
 *       records operations most needs. Those records are handled by the late-arrival
 *       policy at the Writer (D5b), not here.</li>
 * </ul>
 *
 * <p>The check compares two instance locations, which JSON Schema 2020-12 cannot
 * express, so it lives in code rather than in {@code envelope-v1.schema.json}. The
 * five {@code edge/<type>-clock-skew-future.json} fixtures stay schema-valid and
 * carry {@code $expect: "input-reject"} to mark that this is where they fail.</p>
 */
public final class ClockSkewPolicy {

    /** Field-level reason code carried in the RFC 9457 {@code errors[]} array. */
    public static final String CLOCK_SKEW_CODE = "clock_skew_exceeded";

    /** JSON Pointer of the field the caller has to fix. */
    public static final String CLOCK_SKEW_POINTER = "/ts/device";

    private final Duration tolerance;

    public ClockSkewPolicy(Duration tolerance) {
        this.tolerance = Objects.requireNonNull(tolerance, "tolerance");
        if (tolerance.isNegative()) {
            throw new IllegalArgumentException("tolerance must not be negative");
        }
    }

    /**
     * @return {@code true} when {@code device} is further ahead of {@code server}
     *     than the tolerance allows. Exactly at the tolerance is accepted — a
     *     boundary that rejects is a boundary that surprises someone.
     */
    public boolean exceeded(Instant device, Instant server) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(server, "server");
        return device.isAfter(server.plus(tolerance));
    }

    public Duration tolerance() {
        return tolerance;
    }
}
