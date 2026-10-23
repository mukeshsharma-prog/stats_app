package com.zenner.datahub.stats.writer.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.common.model.StatsEnvelope;
import com.zenner.datahub.stats.common.model.Timestamps;
import com.zenner.datahub.stats.writer.consumer.OmsstatsConsumerDescriptor;
import com.zenner.datahub.stats.writer.log.SafeFailure;
import com.zenner.datahub.stats.writer.log.WriterLogContext;
import com.zenner.datahub.stats.writer.retention.RetentionMetrics;
import com.zenner.datahub.stats.writer.retry.PermanentFailureException;
import com.zenner.datahub.stats.writer.validation.ValidatedRecord;
import com.zenner.datahub.stats.writer.validation.WriterGateMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
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
 * Individual, idempotent omsstats inserts behind the batch-shaped contract.
 *
 * <p>A sibling of {@link SysstatsBatchWriter} — the reference slice — with
 * the same two-stage shape (bind the row outside any transaction, then let the database
 * have its turn) and the same three metered outcomes. What differs is the parse, and
 * it must: <em>the parse is per type, not one shared coercion</em>.
 * omsstats has the simplest payload of the five: five required members, every one an
 * integer on the wire, no optional member and no string-typed number. Each
 * is bound as a {@code BIGINT} and a value the column cannot hold — a JSON integer wider
 * than 64 bits is schema-valid, the schema states no maximum — is {@code unmappable}
 * and dead-letters on its first attempt; it is never zeroed or truncated.
 * </p>
 *
 * <p>{@code ts.converter} is on every production omsstats record. It is preserved in
 * {@code passthrough} with the other envelope members this system never reads, and no
 * timestamp is normalised anywhere on this path.</p>
 *
 * <p>Unknown {@code data} members go to {@code data_extra} verbatim and are metered.
 * The schema is {@code additionalProperties: true}, so a member Heimdal adds
 * between releases is valid and must survive the INSERT.</p>
 */
@Component(OmsstatsConsumerDescriptor.BATCH_WRITER_BEAN)
public final class OmsstatsBatchWriter implements StatsBatchWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(OmsstatsBatchWriter.class);

    private static final StatType TYPE = OmsstatsConsumerDescriptor.TYPE;

    /**
     * The five members omsstats-v1.schema.json requires, in the order they are bound.
     * {@code OmsstatsColumnMappingTest} checks this list against the normative schema, so
     * a member added to the schema and not here fails the build rather than becoming a
     * column that is silently always null.
     */
    static final List<String> REQUIRED_FIELDS =
            List.of("rx_nr_pkts", "rx_valid_nr_pkts", "rx_crc_bad", "device_count", "timespan");

    /** omsstats names no optional member; the list exists so the mapping test reads one shape per type. */
    static final List<String> OPTIONAL_FIELDS = List.of();

    /** Everything with a typed column; anything else is preserved in {@code data_extra}. */
    static final Set<String> MAPPED_DATA_FIELDS = mappedFields();

    private static final String INSERT_SQL =
            """
            INSERT INTO %s (
                uid, tenant, maintenant, clsbox, ts_server, ts_device,
                record_version, schema_version, passthrough, data_extra,
                rx_nr_pkts, rx_valid_nr_pkts, rx_crc_bad, device_count, timespan
            ) VALUES (
                :uid, :tenant, :maintenant, :clsbox, :ts_server, :ts_device,
                :record_version, :schema_version,
                CAST(:passthrough AS jsonb), CAST(:data_extra AS jsonb),
                :rx_nr_pkts, :rx_valid_nr_pkts, :rx_crc_bad, :device_count, :timespan
            )
            ON CONFLICT (ts_device, uid) DO NOTHING
            """
                    .formatted(TYPE.wireName());

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final WriterBatchProperties properties;
    private final ObjectMapper objectMapper;
    private final WriterGateMetrics gateMetrics;
    private final Counter duplicates;
    private final Counter unmappedDataFields;
    private final Counter writeFailures;
    private final Counter persisted;
    private final Counter postgresErrors;

    public OmsstatsBatchWriter(
            NamedParameterJdbcTemplate jdbc,
            TransactionTemplate transactions,
            WriterBatchProperties properties,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            WriterGateMetrics gateMetrics,
            RetentionMetrics retentionMetrics) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.gateMetrics = Objects.requireNonNull(gateMetrics, "gateMetrics");
        // The gate's and retention's series for this type export at zero from startup
        // too: a deployed type is exactly one with a writer, and a series that
        // waits for its first event or its first maintenance pass is absent until then.
        gateMetrics.registerFor(TYPE);
        Objects.requireNonNull(retentionMetrics, "retentionMetrics").registerFor(TYPE);
        Objects.requireNonNull(meterRegistry, "meterRegistry");
        // The same five series the lorastats writer registers, under this type's label,
        // all registered up front so each exports at zero from startup.
        this.duplicates =
                Counter.builder(LorastatsBatchWriter.DUPLICATE_METRIC)
                        .description("Rows suppressed by the (ts_device, uid) idempotency key")
                        .tag("type", TYPE.wireName())
                        .register(meterRegistry);
        this.unmappedDataFields =
                Counter.builder(LorastatsBatchWriter.UNMAPPED_DATA_METRIC)
                        .description(
                                "Records carrying a data member the schema does not name, "
                                        + "preserved in data_extra")
                        .tag("type", TYPE.wireName())
                        .register(meterRegistry);
        this.writeFailures =
                Counter.builder(LorastatsBatchWriter.WRITE_FAILED_METRIC)
                        .description("Records whose write failed and were not acknowledged")
                        .tag("type", TYPE.wireName())
                        .register(meterRegistry);
        this.persisted =
                Counter.builder(LorastatsBatchWriter.PERSISTED_METRIC)
                        .description("Records inserted in successfully committed PostgreSQL transactions")
                        .tag("type", TYPE.wireName())
                        .register(meterRegistry);
        this.postgresErrors =
                Counter.builder(LorastatsBatchWriter.POSTGRES_ERROR_METRIC)
                        .description("Record writes failing with a database access or transaction exception")
                        .tag("type", TYPE.wireName())
                        .register(meterRegistry);
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
            // Two stages, two failure meanings: binding is payload work and
            // opens no transaction; the INSERT is the database's turn. A record nobody
            // can map is `unmappable`, never `write_failed`.
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
                                "omsstats payload could NOT BE MAPPED to a row; no transaction was "
                                        + "opened and the delivery is not acknowledged");
                results.add(RecordWriteResult.failed(index, notMappable));
                continue;
            }

            try {
                int affectedRows = insertOne(parameters);
                if (affectedRows == 1) {
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
                // Never silent, never dropped: the consumer hands the delivery to the
                // failure arm. The failure is DESCRIBED, never passed.
                writeFailures.increment();
                if (isDatabaseFailure(failure)) {
                    postgresErrors.increment();
                }
                WriterLogContext.errorCode("write_failed");
                LOGGER.atError()
                        .addKeyValue("event", "write_failed")
                        .addKeyValue("recordIndex", index)
                        .addKeyValue("cause", SafeFailure.describe(failure))
                        .log(
                                "omsstats INSERT failed; delivery is not acknowledged and is handed "
                                        + "to the failure arm -- a retry tier, or straight to the DLQ "
                                        + "if the cause is permanent");
                results.add(RecordWriteResult.failed(index, failure));
            }
        }
        return List.copyOf(results);
    }

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
        JsonNode data = record.data();
        if (data == null || !data.isObject()) {
            throw new PermanentFailureException("data must be a JSON object");
        }

        String dataExtra = dataExtraJson(data);
        if (dataExtra != null) {
            unmappedDataFields.increment();
            LOGGER.atDebug()
                    .addKeyValue("event", "unmapped_data_preserved")
                    .addKeyValue("members", unmappedFieldNames(data))
                    .log("Preserving unmapped omsstats data members in data_extra");
        }

        MapSqlParameterSource parameters = new MapSqlParameterSource();
        parameters
                .addValue("uid", requiredText(record.uid(), "uid"))
                .addValue("tenant", requiredText(record.tenant(), "tenant"))
                .addValue("maintenant", requiredText(record.maintenant(), "maintenant"))
                // One canonical case before the index sees it; Locale.ROOT so a
                // Turkish-locale host cannot map 'i' outside A-F and fail the CHECK.
                .addValue(
                        "clsbox",
                        requiredText(record.clsbox(), "clsbox").toUpperCase(Locale.ROOT))
                .addValue("ts_server", timestamp(ts.server(), "ts.server"))
                .addValue("ts_device", timestamp(ts.device(), "ts.device"))
                // Stored as received, never a discriminator.
                .addValue("record_version", shortOrNull(record.version()), Types.SMALLINT)
                // Which of OUR schemas admitted the row, from the validator's result.
                .addValue("schema_version", validated.schemaVersion())
                .addValue("passthrough", passthroughJson(record), Types.VARCHAR)
                .addValue("data_extra", dataExtra, Types.VARCHAR)
                // Required members: the gate has proved presence and JSON type; the parse
                // here is the column's, and a value BIGINT cannot hold is unmappable rather
                // than silently narrowed. timespan is an INTEGER on this type --
                // the string "300" is sysstats' spelling, not this one's.
                .addValue("rx_nr_pkts", WireNumbers.requiredInteger(data, "rx_nr_pkts"))
                .addValue("rx_valid_nr_pkts", WireNumbers.requiredInteger(data, "rx_valid_nr_pkts"))
                .addValue("rx_crc_bad", WireNumbers.requiredInteger(data, "rx_crc_bad"))
                .addValue("device_count", WireNumbers.requiredInteger(data, "device_count"))
                .addValue("timespan", WireNumbers.requiredInteger(data, "timespan"));
        return parameters;
    }

    /**
     * The envelope members production sends that this system preserves and never
     * reads, in the wire's own shape and units — the same
     * projection the lorastats writer stores, so the API reads one {@code passthrough}
     * shape for every type.
     */
    private String passthroughJson(StatsEnvelope record) {
        Timestamps ts = record.ts();
        ObjectNode passthrough = objectMapper.createObjectNode();

        ObjectNode timestamps = objectMapper.createObjectNode();
        putEpochSeconds(timestamps, "datastore", ts.datastore());
        putEpochSeconds(timestamps, "converter", ts.converter());
        putEpochSeconds(timestamps, "deviceOrig", ts.deviceOrig());
        if (ts.expiresOn() != null) {
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
            // Permanent: no redelivery changes the value. The envelope refuses it at
            // Input now; this is the backstop for anything already queued.
            throw new PermanentFailureException("version does not fit the record_version column");
        }
        return version.shortValue();
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            // Permanent, as above: the envelope requires a non-blank value now.
            throw new PermanentFailureException(field + " must be present");
        }
        return value;
    }

    private static Set<String> mappedFields() {
        List<String> all = new ArrayList<>(REQUIRED_FIELDS);
        all.addAll(OPTIONAL_FIELDS);
        return Set.copyOf(all);
    }
}
