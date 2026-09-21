package com.zenner.datahub.stats.writer.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.common.model.StatsEnvelope;
import com.zenner.datahub.stats.writer.log.SafeFailure;
import com.zenner.datahub.stats.writer.log.WriterLogContext;
import com.zenner.datahub.stats.common.model.Timestamps;
import com.zenner.datahub.stats.writer.consumer.LorastatsConsumerDescriptor;
import com.zenner.datahub.stats.writer.persistence.LorastatsDialects.Dialect;
import com.zenner.datahub.stats.writer.persistence.LorastatsDialects.Normalised;
import com.zenner.datahub.stats.writer.validation.ValidatedRecord;
import com.zenner.datahub.stats.writer.validation.WriterGateMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Individual, idempotent lorastats inserts behind the T-021 list contract.
 *
 * <p>Requirements STA-21A, STA-25A, STA-27A; decisions G3/D11 (batch-shaped
 * interface, individual writes first), ADR-005 (duplicate outcome is
 * {@code ON CONFLICT DO NOTHING}, metered), ADR-003 D2/D7 (typed columns, counters
 * as {@code bigint}, {@code schema_version}), ADR-004 and ADR-007 §2 (the record
 * survives the round trip intact), and D14/D14b (unknown {@code data} members are
 * preserved, never dropped).</p>
 *
 * <p>Since T-057 the input is a {@link ValidatedRecord}: the payload has already passed
 * its per-type schema at {@link com.zenner.datahub.stats.writer.validation.PayloadGate},
 * and the schema label that admitted it travels with it into {@code schema_version}.
 * Since T-063 the payload is <strong>normalised before it is mapped</strong> — all three
 * production dialects reach these columns in one canonical shape (DL-105 rule 2), and
 * the {@code data_extra} residue is taken from that shape rather than from the wire.</p>
 *
 * <p><strong>One round trip is no longer byte-exact, and that is the decision.</strong> A
 * dialect-B or dialect-C record rebuilds with bare names and integral values rather than
 * the spelling it arrived in. ADR-004's byte-identical guarantee is about
 * <em>publication</em> — what Input republishes, and what the retry arm hands on, which
 * is what keeps {@code (ts_device, uid)} suppression correct. Storage was always
 * value-preserving rather than encoding-preserving (ADR-003 D2), and DL-105 rule 2 is
 * what makes one API shape possible for a type that arrives in three.</p>
 */
@Component(LorastatsConsumerDescriptor.BATCH_WRITER_BEAN)
public final class LorastatsBatchWriter implements StatsBatchWriter {

    public static final String DUPLICATE_METRIC = "records_duplicate_suppressed";
    public static final String UNMAPPED_DATA_METRIC = "records_unmapped_data_field";
    public static final String WRITE_FAILED_METRIC = "records_write_failed";
    public static final String PERSISTED_METRIC = "records_persisted";
    public static final String POSTGRES_ERROR_METRIC = "records_postgres_errors";

    /**
     * DL-105 rule 2: the dialect is a metric label, never a column. This answers the
     * only question the distinction actually raises — "how much of the fleet is still on
     * C?" — without pushing a three-way branch onto every consumer of the table.
     *
     * <p>The name is <em>composed</em> from the wire name rather than written as a
     * literal. Spelling it out would hard-code one stat type into main source, which is
     * the shape {@code StatTypeFanOutTest} forbids so that adding a type stays additive
     * (STA-22, trap 15).</p>
     */
    public static final String DIALECT_METRIC =
            "records_" + LorastatsConsumerDescriptor.TYPE.wireName() + "_dialect";

    private static final Logger LOGGER = LoggerFactory.getLogger(LorastatsBatchWriter.class);

    private static final StatType TYPE = LorastatsConsumerDescriptor.TYPE;

    /**
     * Every {@code data} member lorastats-v1.schema.json names, in canonical (bare)
     * spelling. Anything outside this set has no typed column and is preserved in
     * {@code data_extra} (D14b) — which is why the residue is taken <em>after</em>
     * dialect normalisation and not before. Declared once, by
     * {@link LorastatsDialects}, and checked against the normative schema by
     * {@code LorastatsColumnMappingTest}, so a member added to the schema and not here
     * is a build failure rather than a column that is silently always null.
     */
    static final Set<String> MAPPED_DATA_FIELDS = LorastatsDialects.CANONICAL_FIELDS;

    private static final String INSERT_SQL =
            """
            INSERT INTO %s (
                uid, tenant, maintenant, clsbox, ts_server, ts_device,
                record_version, schema_version, passthrough, data_extra,
                rx_nr_pkts, rx_crc_missing, rx_crc_bad,
                rx_short_pkt, tx_success, tx_failed, tx_peek, tx_peek_index_zero,
                tx_override, tx_busy, tx_dequeue_error, tx_type_beacon,
                chip_comm_error, util_fopen_failed, fsk_received, rx_type_arol,
                timespan
            ) VALUES (
                :uid, :tenant, :maintenant, :clsbox, :ts_server, :ts_device,
                :record_version, :schema_version,
                CAST(:passthrough AS jsonb), CAST(:data_extra AS jsonb),
                :rx_nr_pkts, :rx_crc_missing, :rx_crc_bad,
                :rx_short_pkt, :tx_success, :tx_failed, :tx_peek, :tx_peek_index_zero,
                :tx_override, :tx_busy, :tx_dequeue_error, :tx_type_beacon,
                :chip_comm_error, :util_fopen_failed, :fsk_received, :rx_type_arol,
                :timespan
            )
            ON CONFLICT (ts_device, uid) DO NOTHING
            """
                    .formatted(TYPE.wireName());

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final WriterBatchProperties properties;
    private final ObjectMapper objectMapper;
    private final Counter duplicates;
    private final Counter unmappedDataFields;
    private final Counter writeFailures;
    private final WriterGateMetrics gateMetrics;
    private final Map<Dialect, Counter> dialects = new EnumMap<>(Dialect.class);
    private final Counter persisted;
    private final Counter postgresErrors;

    public LorastatsBatchWriter(
            NamedParameterJdbcTemplate jdbc,
            TransactionTemplate transactions,
            WriterBatchProperties properties,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            WriterGateMetrics gateMetrics) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.gateMetrics = Objects.requireNonNull(gateMetrics, "gateMetrics");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.duplicates =
                Counter.builder(DUPLICATE_METRIC)
                        .description("Rows suppressed by the (ts_device, uid) idempotency key")
                        .tag("type", TYPE.wireName())
                        .register(meterRegistry);
        this.unmappedDataFields =
                Counter.builder(UNMAPPED_DATA_METRIC)
                        .description(
                                "Records carrying a data member the schema does not name, "
                                        + "preserved in data_extra (D14b)")
                        .tag("type", TYPE.wireName())
                        .register(meterRegistry);
        this.writeFailures =
                Counter.builder(WRITE_FAILED_METRIC)
                        .description("Records whose write failed and were not acknowledged")
                        .tag("type", TYPE.wireName())
                        .register(meterRegistry);
        // All three are registered up front, so each exports at zero from startup. A
        // counter that only appears after its first occurrence is absent exactly when it
        // is being looked for -- and the question this metric answers is "is dialect C
        // still in the field", which a missing series answers wrongly and silently.
        for (Dialect dialect : Dialect.values()) {
            dialects.put(
                    dialect,
                    Counter.builder(DIALECT_METRIC)
                            .description(
                                    "Records normalised from each known payload dialect "
                                            + "(DL-105 rule 2)")
                            .tag("dialect", dialect.label())
                            .register(meterRegistry));
        }
        this.persisted = Counter.builder(PERSISTED_METRIC)
                .description("Records inserted in successfully committed PostgreSQL transactions")
                .tag("type", TYPE.wireName()).register(meterRegistry);
        this.postgresErrors = Counter.builder(POSTGRES_ERROR_METRIC)
                .description("Record writes failing with a database access or transaction exception")
                .tag("type", TYPE.wireName()).register(meterRegistry);
    }

    @Override
    public List<RecordWriteResult> write(List<ValidatedRecord> records) {
        Objects.requireNonNull(records, "records");
        if (records.isEmpty()) {
            throw new IllegalArgumentException("records must not be empty");
        }
        if (records.size() > properties.maxRecords()) {
            throw new IllegalArgumentException(
                    "records size must not exceed configured maxRecords="
                            + properties.maxRecords());
        }

        List<RecordWriteResult> results = new ArrayList<>(records.size());
        for (int index = 0; index < records.size(); index++) {
            // TWO STAGES, TWO FAILURE MEANINGS. Binding the row is payload work and
            // happens before any transaction opens; the INSERT is the database's turn.
            // Collapsing them was a real defect: a record in an unknown dialect never
            // reaches PostgreSQL, and counting it as a WRITE failure sends whoever reads
            // records_write_failed to a database that is behaving perfectly.
            final MapSqlParameterSource parameters;
            try {
                parameters = parameters(records.get(index));
            } catch (RuntimeException notMappable) {
                gateMetrics.payloadRejected(TYPE, WriterGateMetrics.REASON_UNMAPPABLE);
                WriterLogContext.errorCode(WriterGateMetrics.REASON_UNMAPPABLE);
                LOGGER.atError()
                        .addKeyValue("event", "payload_rejected")
                        .addKeyValue("reason", WriterGateMetrics.REASON_UNMAPPABLE)
                        .addKeyValue("recordIndex", index)
                        .addKeyValue("cause", SafeFailure.describe(notMappable))
                        .log(
                                "lorastats payload could NOT BE MAPPED to a row; no transaction was "
                                        + "opened and the delivery is not acknowledged");
                results.add(RecordWriteResult.failed(index, notMappable));
                continue;
            }

            try {
                int affectedRows = insertOne(parameters);
                if (affectedRows == 1) {
                    // insertOne returns only after TransactionTemplate commits.
                    persisted.increment();
                    results.add(RecordWriteResult.inserted(index));
                } else if (affectedRows == 0) {
                    duplicates.increment();
                    results.add(RecordWriteResult.duplicateSuppressed(index));
                } else {
                    throw new IllegalStateException(
                            "A single-record insert affected an unexpected row count");
                }
            } catch (RuntimeException failure) {
                // Never silent (claude/CLAUDE.md §9). Since T-024 the delivery is not
                // dropped either: the consumer hands it to FailedDeliveryRouter, which
                // confirms a retry or dead-letter copy before the original is ACKed.
                //
                // The failure is DESCRIBED, never passed to the logger. This is the exact
                // path guard P2 was written for (DEVELOPMENT.md §7): PostgreSQL puts the
                // offending values in its DETAIL: line, the driver puts that line in the
                // exception message, and a throwable argument prints it at ERROR — where
                // DEBUG-gating the payload never sees it. uid stays: it is the correlation
                // ID by decision (D1), and it is what makes a DLQ message findable.
                writeFailures.increment();
                if (isDatabaseFailure(failure)) {
                    postgresErrors.increment();
                }
                WriterLogContext.errorCode("write_failed");
                // uid is carried explicitly as well as in the MDC: this is a batch-shaped
                // API, and once G3 batches for real the MDC will name the delivery, not
                // the record at this index.
                LOGGER.atError()
                        .addKeyValue("event", "write_failed")
                        .addKeyValue("recordIndex", index)
                        .addKeyValue("cause", SafeFailure.describe(failure))
                        .log(
                                "lorastats INSERT failed; delivery is not acknowledged and is handed "
                                        + "to the failure arm -- a retry tier, or straight to the DLQ "
                                        + "if the cause is permanent");
                results.add(RecordWriteResult.failed(index, failure));
            }
        }
        return List.copyOf(results);
    }

    /**
     * Whether a failed write is the database's (T-027): a JDBC, Spring data-access or
     * transaction exception anywhere in a bounded cause chain. Only the TYPE is inspected
     * -- no driver message becomes a label or a log value (guard P2).
     */
    private static boolean isDatabaseFailure(Throwable failure) {
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (failure instanceof SQLException
                    || failure instanceof DataAccessException
                    || failure instanceof TransactionException) {
                return true;
            }
        }
        return false;
    }

    /**
     * The database's turn, and nothing else. The row was bound by the caller, outside
     * this transaction: everything {@code parameters()} does is payload work -- dialect
     * detection, range-checked coercion, the {@code data_extra} residue -- and all of it
     * can throw. Evaluated inside the lambda it would open a transaction, do CPU work and
     * roll back, once per bad record; a systematic dialect fault at 115.2m records/day
     * would spend a BEGIN/ROLLBACK on every one of them for nothing.
     */
    private int insertOne(MapSqlParameterSource parameters) {
        Objects.requireNonNull(parameters, "parameters");
        Integer affectedRows = transactions.execute(status -> jdbc.update(INSERT_SQL, parameters));
        if (affectedRows == null) {
            throw new IllegalStateException("Transaction completed without an insert result");
        }
        return affectedRows;
    }

    private MapSqlParameterSource parameters(ValidatedRecord validated) {
        StatsEnvelope record = validated.envelope();
        if (record.type() != TYPE) {
            throw new IllegalArgumentException("record type must be " + TYPE.wireName());
        }
        Timestamps ts = Objects.requireNonNull(record.ts(), "record.ts");

        // T-063 / DL-105. Normalisation comes FIRST, and everything below reads its
        // output rather than the raw payload. Taking the counters or the data_extra
        // residue from the wire shape instead would mean that on dialect C every one of
        // the fifteen lora_-prefixed counters is an "unmapped" member: the typed columns
        // land NULL and records_unmapped_data_field -- whose whole job is to say
        // "Heimdal added a field" -- fires on ordinary traffic every ten minutes.
        Normalised normalised = LorastatsDialects.normalise(record.data());
        dialects.get(normalised.dialect()).increment();
        JsonNode data = normalised.data();

        String dataExtra = dataExtraJson(data);
        if (dataExtra != null) {
            unmappedDataFields.increment();
            LOGGER.atDebug()
                    .addKeyValue("event", "unmapped_data_preserved")
                    .addKeyValue("members", unmappedFieldNames(data))
                    .log("Preserving unmapped lorastats data members in data_extra");
        }

        MapSqlParameterSource parameters = new MapSqlParameterSource();
        parameters
                .addValue("uid", requiredText(record.uid(), "uid"))
                .addValue("tenant", requiredText(record.tenant(), "tenant"))
                .addValue("maintenant", requiredText(record.maintenant(), "maintenant"))
                // Uppercased to ONE canonical form before it reaches the index
                // (DL-115). The wire admits either case; storing either case splits
                // one device across two keys in
                // idx_lorastats_tenant_clsbox_device_uid, and the API's
                // case-insensitive clsbox filter then returns a silently partial
                // page. Locale.ROOT because the Turkish locale maps 'i' outside
                // A-F and would fail the V5 CHECK on a host nobody tested on.
                .addValue(
                        "clsbox",
                        requiredText(record.clsbox(), "clsbox").toUpperCase(Locale.ROOT))
                .addValue("ts_server", timestamp(ts.server(), "ts.server"))
                .addValue("ts_device", timestamp(ts.device(), "ts.device"))
                // Heimdal's version, stored as received. ADR-007 §4: NEVER a payload
                // discriminator -- all three incompatible lorastats dialects carry 1.
                .addValue("record_version", shortOrNull(record.version()), Types.SMALLINT)
                // ADR-003 D7: which of OUR schemas validated the row, taken from the
                // validator's own result since T-057. A constant here would answer a
                // different question -- which schema the author believed was current --
                // and the two agree only until a v2 exists, at which point every row
                // already stored claims the wrong one and nothing fails.
                .addValue("schema_version", validated.schemaVersion())
                .addValue("passthrough", passthroughJson(record), Types.VARCHAR)
                .addValue("data_extra", dataExtra, Types.VARCHAR);

        for (String field : ORDERED_REQUIRED_FIELDS) {
            parameters.addValue(field, requiredLong(data, field));
        }
        for (String field : ORDERED_OPTIONAL_FIELDS) {
            parameters.addValue(field, optionalLong(data, field));
        }
        return parameters;
    }

    /**
     * Declared once, in {@link LorastatsDialects}, because the same two lists are the
     * detection evidence there and the column binding here. Two copies of them would
     * drift on the day a counter is added, and the drift is silent in one direction: a
     * field the detector knows about and this class does not simply stops reaching its
     * column.
     */
    private static final List<String> ORDERED_REQUIRED_FIELDS = LorastatsDialects.REQUIRED_FIELDS;

    private static final List<String> ORDERED_OPTIONAL_FIELDS = LorastatsDialects.OPTIONAL_FIELDS;

    /**
     * The envelope members production sends that this system preserves and never
     * reads: {@code ts.datastore}, {@code ts.converter}, {@code ts.expiresOn} and
     * {@code status} (ADR-003 "Required table and index direction", ADR-007 §2), plus
     * the two Heimdal has since been seen to add — {@code ts.deviceOrig} and
     * {@code meta} (DL-146).
     *
     * <p>Written in the wire's own shape and units — epoch <em>seconds</em>, and
     * {@code expiresOn} in whichever of its two spellings arrived — so the
     * stored value reconstructs the received one. Returns {@code null} when the
     * record carries none of them, which is every fixture written before 29 Aug 2026.</p>
     */
    private String passthroughJson(StatsEnvelope record) {
        Timestamps ts = record.ts();
        ObjectNode passthrough = objectMapper.createObjectNode();

        ObjectNode timestamps = objectMapper.createObjectNode();
        putEpochSeconds(timestamps, "datastore", ts.datastore());
        putEpochSeconds(timestamps, "converter", ts.converter());
        putEpochSeconds(timestamps, "deviceOrig", ts.deviceOrig());
        if (ts.expiresOn() != null) {
            // String or {"$date": ...} - whichever form arrived is the form stored (DL-146).
            timestamps.set("expiresOn", ts.expiresOn());
        }
        if (!timestamps.isEmpty()) {
            passthrough.set("ts", timestamps);
        }
        if (record.status() != null) {
            passthrough.set("status", objectMapper.valueToTree(record.status()));
        }
        if (record.meta() != null) {
            passthrough.set("meta", record.meta());
        }
        return passthrough.isEmpty() ? null : writeJson(passthrough);
    }

    /**
     * Unknown {@code data} members, preserved verbatim (D14, D14b).
     *
     * <p>The lorastats schema is {@code additionalProperties: true}, so a member
     * Heimdal adds between releases is valid and must survive. Typed columns cannot
     * hold it; dropping it is what ADR-003 D2 rejected in writing. Returns
     * {@code null} — leaving the column NULL and costing one null-bitmap bit — when
     * the payload is exactly the schema, which is the overwhelmingly common case.</p>
     */
    private String dataExtraJson(JsonNode data) {
        ObjectNode extra = objectMapper.createObjectNode();
        for (Iterator<String> names = data.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!MAPPED_DATA_FIELDS.contains(name)) {
                extra.set(name, data.get(name));
            }
        }
        return extra.isEmpty() ? null : writeJson(extra);
    }

    private static List<String> unmappedFieldNames(JsonNode data) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> fields = data.fieldNames(); fields.hasNext(); ) {
            String name = fields.next();
            if (!MAPPED_DATA_FIELDS.contains(name)) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private String writeJson(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException failure) {
            // Cannot happen for an ObjectNode built from already-parsed input, and if it
            // somehow does, losing the record is preferable to storing a truncated one.
            throw new IllegalStateException("Failed to serialise preserved JSON", failure);
        }
    }

    private static void putEpochSeconds(ObjectNode target, String field, Instant value) {
        if (value != null) {
            target.put(field, value.getEpochSecond());
        }
    }

    private static Timestamp timestamp(Instant value, String field) {
        return Timestamp.from(Objects.requireNonNull(value, field));
    }

    private static Short shortOrNull(Integer version) {
        if (version == null) {
            return null;
        }
        if (version < Short.MIN_VALUE || version > Short.MAX_VALUE) {
            throw new IllegalArgumentException("version does not fit the record_version column");
        }
        return version.shortValue();
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be present");
        }
        return value;
    }

    private static long requiredLong(JsonNode data, String field) {
        Long value = optionalLong(data, field);
        if (value == null) {
            throw new IllegalArgumentException("data." + field + " must be present");
        }
        return value;
    }

    /**
     * Reads one counter, range-checked against the signed {@code BIGINT} column
     * before conversion (ADR-003 D2, STA-04C). The schema's digit pattern is wider
     * than {@code BIGINT}, and that gap is not expressible as a regex, so an
     * unchecked conversion corrupts the value with no error anywhere.
     */
    private static Long optionalLong(JsonNode data, String field) {
        JsonNode value = data.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("data." + field + " must be a BIGINT value");
        }
        return value.longValue();
    }
}
