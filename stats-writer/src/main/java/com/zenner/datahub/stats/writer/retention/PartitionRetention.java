package com.zenner.datahub.stats.writer.retention;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.writer.log.SafeFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Retention: {@code DETACH PARTITION … CONCURRENTLY} then {@code DROP TABLE}, never row
 * deletion (T-025; STA-23A, STA-23C; ADR-003 D1, D3, D4).
 *
 * <h2>Why dropping a partition and not deleting rows</h2>
 *
 * <p>Dropping a partition is a catalogue operation — milliseconds, no dead tuples, no
 * vacuum debt, no index bloat. At up to 115.2m records a day an unbounded {@code DELETE}
 * produces long transactions, lock contention and table bloat, which is why
 * {@code claude/CLAUDE.md} §6 lists row-by-row deletion at this volume as a trap and why
 * ADR-003 D1 chose {@code RANGE (ts_device)} partitioning in the first place. The
 * retention basis and the dominant query pattern are the same column, so one partition key
 * serves both.</p>
 *
 * <h2>Three PostgreSQL behaviours this class is built around</h2>
 *
 * <p>All three were verified against PostgreSQL 17 before this class was written, because
 * two of them fail in ways that leave ingestion working perfectly:</p>
 *
 * <ol>
 *   <li><strong>{@code DETACH CONCURRENTLY} cannot run inside a transaction block.</strong>
 *       It is a hard {@code ERROR}, not a degradation. {@link PartitionSession} forces
 *       auto-commit for exactly this statement, and nothing here opens a transaction.</li>
 *   <li><strong>An interrupted detach leaves the partition "pending detach"</strong>
 *       ({@code pg_inherits.inhdetachpending}), and in that state <em>every</em> later
 *       {@code DETACH CONCURRENTLY} on the same parent fails with
 *       {@code partition "…" already pending detach}. Meanwhile {@code INSERT} and
 *       partition pre-creation keep working — so a run killed by a rolling deploy or a
 *       statement timeout wedges retention for that stat type <em>permanently</em>, and
 *       the only symptom is disk. Every run therefore begins by finalising a pending
 *       detach rather than assuming the last one finished.</li>
 *   <li><strong>Between the detach and the drop the table still exists</strong>, standalone
 *       and still holding its rows, with {@code relispartition} now false. A crash in that
 *       gap leaks a day of storage that nothing would ever reclaim, so
 *       {@link #sweepOrphans} drops leftovers it can name, date and place outside the
 *       window.</li>
 * </ol>
 *
 * <h2>What this class refuses to drop</h2>
 *
 * <p>Dropping is irreversible short of a per-partition restore (ADR-003 D5), so eligibility
 * is proved rather than assumed. A child qualifies only if its name is one
 * {@link PartitionNaming} produced, <em>and</em> its catalogue bound is exactly the bound
 * that name implies, <em>and</em> that day is wholly outside the window
 * ({@link RetentionWindow}). Anything else — a hand-made child, a name that does not
 * parse, a bound that disagrees — is logged and kept. The safe direction here is to retain
 * something that could have gone, never to drop something that could not.</p>
 */
@Component
public class PartitionRetention {

    private static final Logger LOGGER = LoggerFactory.getLogger(PartitionRetention.class);

    /** Children of the parent, with their bounds and whether a detach is half-finished. */
    private static final String CHILDREN_SQL =
            "SELECT child.relname, pg_get_expr(child.relpartbound, child.oid), "
                    + "inheritance.inhdetachpending "
                    + "FROM pg_inherits inheritance "
                    + "JOIN pg_class child ON child.oid = inheritance.inhrelid "
                    + "WHERE inheritance.inhparent = to_regclass(?)";

    /**
     * Tables that look like this type's partitions but belong to no parent — the residue
     * of a run that died between the detach and the drop. Restricted to the parent's own
     * schema so an identically named table elsewhere on the search path is out of reach.
     */
    private static final String ORPHANS_SQL =
            "SELECT candidate.relname FROM pg_class candidate "
                    + "WHERE candidate.relkind = 'r' "
                    + "AND NOT candidate.relispartition "
                    + "AND candidate.relnamespace = "
                    + "(SELECT parent.relnamespace FROM pg_class parent "
                    + "WHERE parent.oid = to_regclass(?)) "
                    + "AND candidate.relname LIKE ? ESCAPE '\\'";

    private final PartitionSession session;
    private final RetentionWindow window;
    private final RetentionMetrics metrics;

    public PartitionRetention(
            DataSource dataSource, RetentionWindow window, RetentionMetrics metrics) {
        this.session = new PartitionSession(Objects.requireNonNull(dataSource, "dataSource"));
        this.window = Objects.requireNonNull(window, "window");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Runs one retention pass for {@code type} under its advisory lock.
     *
     * <p>The lock is required even though ADR-003 D8 deploys one instance per stat type,
     * because D8 also runs <em>N replicas</em> of that instance, and N replicas racing to
     * detach the same partition is precisely what D3 exists to prevent. A replica that does
     * not get the lock has not missed anything — another one is doing the work.</p>
     */
    public RetentionResult sweep(StatType type) {
        Objects.requireNonNull(type, "type");
        return session.withLock(
                        type, "apply retention for " + type.wireName(), connection -> run(type, connection))
                .orElseGet(RetentionResult::lockNotAcquired);
    }

    private RetentionResult run(StatType type, Connection connection) throws SQLException {
        LocalDate cutoffDay = window.cutoffDay(type);

        int finalised = finaliseAbandonedDetaches(type, connection);
        List<LivePartition> live = livePartitions(type, connection, true);

        int dropped = 0;
        for (LivePartition partition : live) {
            if (!window.isDroppable(type, partition.day())) {
                continue;
            }
            if (detachAndDrop(type, connection, partition)) {
                dropped++;
            }
        }

        int orphansDropped = sweepOrphans(type, connection);
        publishOldestPartitionAge(type, connection);

        return new RetentionResult(true, dropped, orphansDropped, finalised, cutoffDay);
    }

    /**
     * Completes any detach a previous run left half-done, so this one can detach at all.
     *
     * <p>Logged at {@code WARN}: nothing about normal operation produces a pending detach,
     * so seeing one means a maintenance run was killed — worth knowing about even though
     * this method has just repaired it.</p>
     */
    private int finaliseAbandonedDetaches(StatType type, Connection connection)
            throws SQLException {
        List<String> pending = new ArrayList<>();
        for (ChildRelation child : children(type, connection)) {
            if (child.detachPending()) {
                pending.add(child.name());
            }
        }
        for (String name : pending) {
            execute(
                    connection,
                    "ALTER TABLE "
                            + PartitionNaming.quotedIdentifier(type.wireName())
                            + " DETACH PARTITION "
                            + PartitionNaming.quotedIdentifier(name)
                            + " FINALIZE");
            LOGGER.atWarn()
                    .addKeyValue("event", "detach_finalised")
                    .addKeyValue("statType", type.wireName())
                    .addKeyValue("partition", name)
                    .log(
                            "Completed a partition detach that an earlier maintenance run left "
                                    + "unfinished; until now every DETACH CONCURRENTLY on this table "
                                    + "was failing while ingestion carried on normally");
        }
        return pending.size();
    }

    /**
     * The children this class is willing to act on: name parses, bound agrees, detach not
     * pending. Everything rejected here is left alone, and reported when {@code report} is
     * set.
     *
     * <p>{@code report} exists because this scan runs twice per sweep — once to decide what
     * to drop, once afterwards to measure what is left. Logging both times would print
     * every refusal twice per run and make a single misnamed partition look like two.</p>
     */
    private List<LivePartition> livePartitions(
            StatType type, Connection connection, boolean report) throws SQLException {
        List<LivePartition> live = new ArrayList<>();
        for (ChildRelation child : children(type, connection)) {
            if (child.detachPending()) {
                // Just finalised above; it is an orphan now, not a partition.
                continue;
            }
            Optional<LocalDate> day = PartitionNaming.dayOf(type, child.name());
            if (day.isEmpty()) {
                if (report) {
                    LOGGER.atWarn()
                            .addKeyValue("event", "partition_ignored")
                            .addKeyValue("statType", type.wireName())
                            .addKeyValue("partition", child.name())
                            .addKeyValue("reason", "unrecognised_name")
                            .log(
                                    "Ignoring a partition whose name this module did not create; "
                                            + "retention will never drop it");
                }
                continue;
            }
            String expectedBound = PartitionNaming.partitionBound(day.get());
            if (!expectedBound.equals(child.bound())) {
                // Refusing is the whole point. A name that says one day and a bound that
                // says another means dropping it would delete a range nobody expects.
                if (report) {
                    LOGGER.atError()
                            .addKeyValue("event", "partition_ignored")
                            .addKeyValue("statType", type.wireName())
                            .addKeyValue("partition", child.name())
                            .addKeyValue("reason", "bound_mismatch")
                            .addKeyValue("expectedBound", expectedBound)
                            .addKeyValue("foundBound", child.bound())
                            .log(
                                    "Ignoring a partition whose bounds do not match its name; retention "
                                            + "will never drop it");
                }
                continue;
            }
            live.add(new LivePartition(child.name(), day.get()));
        }
        live.sort(Comparator.comparing(LivePartition::day));
        return List.copyOf(live);
    }

    /**
     * Detaches concurrently, then drops. Isolated per partition, because one wedged
     * partition must not stop the others being reclaimed.
     *
     * <p>{@code DROP TABLE} runs only after the detach has succeeded. Dropping a still
     * attached partition would work, and would take an {@code ACCESS EXCLUSIVE} lock on
     * the parent — blocking every writer for the duration, which is the exact cost
     * {@code CONCURRENTLY} is here to avoid (T-055).</p>
     */
    private boolean detachAndDrop(StatType type, Connection connection, LivePartition partition) {
        String parent = PartitionNaming.quotedIdentifier(type.wireName());
        String child = PartitionNaming.quotedIdentifier(partition.name());
        try {
            execute(connection, "ALTER TABLE " + parent + " DETACH PARTITION " + child + " CONCURRENTLY");
        } catch (SQLException | RuntimeException failure) {
            LOGGER.atError()
                    .addKeyValue("event", "partition_detach_failed")
                    .addKeyValue("statType", type.wireName())
                    .addKeyValue("partition", partition.name())
                    .addKeyValue("cause", SafeFailure.describe(failure))
                    .log(
                            "Partition DETACH failed; the partition is still attached and was NOT "
                                    + "dropped. If it was left pending, the next run finalises it");
            return false;
        }
        try {
            execute(connection, "DROP TABLE " + child);
        } catch (SQLException | RuntimeException failure) {
            // Detached but not dropped: the data is out of the table and the storage is
            // not yet reclaimed. sweepOrphans picks it up, here or on the next run.
            LOGGER.atError()
                    .addKeyValue("event", "partition_drop_failed")
                    .addKeyValue("statType", type.wireName())
                    .addKeyValue("partition", partition.name())
                    .addKeyValue("cause", SafeFailure.describe(failure))
                    .log(
                            "Partition was detached but could NOT be dropped; it is now an orphan "
                                    + "holding storage until a later sweep reclaims it");
            return false;
        }
        LOGGER.atInfo()
                .addKeyValue("event", "partition_dropped")
                .addKeyValue("statType", type.wireName())
                .addKeyValue("partition", partition.name())
                .addKeyValue("day", partition.day())
                .addKeyValue("retentionDays", window.retentionDaysFor(type))
                .log("Dropped a partition past the retention window");
        metrics.partitionDropped(type);
        return true;
    }

    /**
     * Drops tables that were detached but never dropped.
     *
     * <p>Bounded three ways so it can only ever finish this module's own work: the name has
     * to be one {@link PartitionNaming} produces, the table has to belong to no parent, and
     * the day has to be outside the window. An orphan inside the window is reported and
     * kept — retention never detaches one, so its existence means a person did.</p>
     */
    private int sweepOrphans(StatType type, Connection connection) throws SQLException {
        int dropped = 0;
        for (String name : orphanCandidates(type, connection)) {
            Optional<LocalDate> day = PartitionNaming.dayOf(type, name);
            if (day.isEmpty()) {
                continue;
            }
            if (!window.isDroppable(type, day.get())) {
                LOGGER.atWarn()
                        .addKeyValue("event", "orphan_ignored")
                        .addKeyValue("statType", type.wireName())
                        .addKeyValue("table", name)
                        .addKeyValue("day", day.get())
                        .log(
                                "A detached table inside the retention window was left alone; "
                                        + "retention never detaches one, so this was done by hand");
                continue;
            }
            try {
                execute(connection, "DROP TABLE " + PartitionNaming.quotedIdentifier(name));
            } catch (SQLException | RuntimeException failure) {
                LOGGER.atError()
                        .addKeyValue("event", "orphan_drop_failed")
                        .addKeyValue("statType", type.wireName())
                        .addKeyValue("table", name)
                        .addKeyValue("cause", SafeFailure.describe(failure))
                        .log("Failed to drop a detached partition table; it keeps holding storage");
                continue;
            }
            dropped++;
            metrics.orphanDropped(type);
            LOGGER.atWarn()
                    .addKeyValue("event", "orphan_dropped")
                    .addKeyValue("statType", type.wireName())
                    .addKeyValue("table", name)
                    .addKeyValue("day", day.get())
                    .log(
                            "Dropped a partition table that an earlier run detached but never "
                                    + "dropped");
        }
        return dropped;
    }

    /**
     * Publishes the age of the oldest live partition after this sweep.
     *
     * <p>The counters cannot report an absence — a retention job that has stopped and one
     * with nothing to do both stop incrementing. This gauge climbs instead.</p>
     *
     * <p>It measures only partitions this class would be willing to drop. One it refuses —
     * a name it did not create, a bound that disagrees — is reported as its own log line
     * and deliberately does not move this gauge, which answers "is the sweep keeping up"
     * rather than "is anything odd attached".</p>
     */
    private void publishOldestPartitionAge(StatType type, Connection connection)
            throws SQLException {
        LocalDate oldest = null;
        for (LivePartition partition : livePartitions(type, connection, false)) {
            if (oldest == null || partition.day().isBefore(oldest)) {
                oldest = partition.day();
            }
        }
        if (oldest == null) {
            // No partitions at all. Reporting a stale age would be worse than reporting
            // nothing: pre-creation failing is its own alert (ADR-003 D1).
            return;
        }
        metrics.oldestPartitionAgeDays(
                type, (int) ChronoUnit.DAYS.between(oldest, window.today()));
    }

    private List<ChildRelation> children(StatType type, Connection connection) throws SQLException {
        List<ChildRelation> children = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(CHILDREN_SQL)) {
            statement.setString(1, type.wireName());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    children.add(
                            new ChildRelation(
                                    result.getString(1), result.getString(2), result.getBoolean(3)));
                }
            }
        }
        return List.copyOf(children);
    }

    private List<String> orphanCandidates(StatType type, Connection connection)
            throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(ORPHANS_SQL)) {
            statement.setString(1, type.wireName());
            // The underscore is a LIKE wildcard, so it is escaped: without this,
            // `omsstats_%` would also match a table called `omsstatsXfoo`.
            statement.setString(2, type.wireName() + "\\_%");
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    names.add(result.getString(1));
                }
            }
        }
        return List.copyOf(names);
    }

    /**
     * One statement, on its own.
     *
     * <p>Deliberately not batched with anything else: several statements in one round trip
     * become an implicit transaction block, and {@code DETACH CONCURRENTLY} refuses to run
     * in one.</p>
     */
    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record ChildRelation(String name, String bound, boolean detachPending) {}

    private record LivePartition(String name, LocalDate day) {}

    /**
     * What one sweep did. {@code lockAcquired == false} means another replica owns this
     * run — the expected outcome on all but one replica (ADR-003 D3), not a failure.
     */
    public record RetentionResult(
            boolean lockAcquired,
            int partitionsDropped,
            int orphansDropped,
            int detachesFinalised,
            LocalDate cutoffDay) {

        public RetentionResult {
            if (partitionsDropped < 0 || orphansDropped < 0 || detachesFinalised < 0) {
                throw new IllegalArgumentException("Invalid retention result");
            }
            if (!lockAcquired
                    && (partitionsDropped != 0 || orphansDropped != 0 || detachesFinalised != 0)) {
                throw new IllegalArgumentException(
                        "A run that did not hold the lock cannot have changed anything");
            }
        }

        static RetentionResult lockNotAcquired() {
            return new RetentionResult(false, 0, 0, 0, null);
        }

        /** Whether this run changed the database at all, for the caller's log level. */
        public boolean changedAnything() {
            return partitionsDropped > 0 || orphansDropped > 0 || detachesFinalised > 0;
        }
    }
}
