package com.zenner.datahub.stats.writer.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.common.model.StatsEnvelope;
import com.zenner.datahub.stats.common.model.Timestamps;
import com.zenner.datahub.stats.writer.consumer.SysstatsConsumerDescriptor;
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
 * Individual, idempotent sysstats inserts behind the batch-shaped contract (T-035,
 * DL-148; STA-21A, STA-25A, STA-27A; ADR-003 D2/D7, ADR-005, D14/D14b, DL-115).
 *
 * <p>A sibling of {@link LorastatsBatchWriter} with the same two-stage shape — bind the
 * row outside any transaction, then let the database have its turn — and the same
 * three metered outcomes. What differs is the parse, and ADR-003 says it must:
 * <em>"the parse is per type, not one shared coercion"</em>. sysstats has one dialect
 * and no {@code lora_} spelling, so there is no normaliser; but it is the type where
 * {@code timespan} is the <strong>string</strong> {@code "300"} while every other type
 * sends an integer, and where the load averages and uptimes are real numbers rather
 * than counters. Each column is parsed for what the wire actually sends, and the one
 * digit-string is range-checked against {@code BIGINT} <em>before</em> conversion
 * (STA-04C) — an overflow dead-letters, and is never zeroed or truncated.</p>
 *
 * <p>Unknown {@code data} members go to {@code data_extra} verbatim and are metered
 * (D14b). The schema is {@code additionalProperties: true}, so a member Heimdal adds
 * between releases is valid and must survive the INSERT.</p>
 */
@Component(SysstatsConsumerDescriptor.BATCH_WRITER_BEAN)
public final class SysstatsBatchWriter implements StatsBatchWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SysstatsBatchWriter.class);

    private static final StatType TYPE = SysstatsConsumerDescriptor.TYPE;

    /**
     * The seven members sysstats-v1.schema.json requires, in the order they are bound.
     * {@code SysstatsColumnMappingTest} checks both lists against the normative schema, so
     * a member added to the schema and not here fails the build rather than becoming a
     * column that is silently always null.
     */
    static final List<String> REQUIRED_FIELDS =
            List.of("memfree", "uptime", "idletime", "load1", "load5", "load15", "runable");

    /** The five optional members — absent on a gateway without the sensor or the link. */
    static final List<String> OPTIONAL_FIELDS =
            List.of("temp", "rtt", "wan_if", "rebootcause", "timespan");

    /** Everything with a typed column; anything else is preserved in {@code data_extra}. */
    static final Set<String> MAPPED_DATA_FIELDS = mappedFields();

    private static final String INSERT_SQL =
            """
            INSERT INTO %s (
                uid, tenant, maintenant, clsbox, ts_server, ts_device,
                record_version, schema_version, passthrough, data_extra,
                memfree, uptime, idletime, load1, load5, load15, runable,
                temp, rtt, wan_if, rebootcause, timespan
            ) VALUES (
                :uid, :tenant, :maintenant, :clsbox, :ts_server, :ts_device,
                :record_version, :schema_version,
                CAST(:passthrough AS jsonb), CAST(:data_extra AS jsonb),
                :memfree, :uptime, :idletime, :load1, :load5, :load15, :runable,
                :temp, :rtt, :wan_if, :rebootcause, :timespan
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

    public SysstatsBatchWriter(
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
        // too (DL-158): a deployed type is exactly one with a writer, and a series that
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
                                        + "preserved in data_extra (D14b)")
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
            // Two stages, two failure meanings (DL-126): binding is payload work and
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
                                "sysstats payload could NOT BE MAPPED to a row; no transaction was "
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
                // failure arm. The failure is DESCRIBED, never passed (guard P2).
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
                                "sysstats INSERT failed; delivery is not acknowledged and is handed "
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
                    .log("Preserving unmapped sysstats data members in data_extra");
        }

        MapSqlParameterSource parameters = new MapSqlParameterSource();
        parameters
                .addValue("uid", requiredText(record.uid(), "uid"))
                .addValue("tenant", requiredText(record.tenant(), "tenant"))
                .addValue("maintenant", requiredText(record.maintenant(), "maintenant"))
                // One canonical case before the index sees it (DL-115); Locale.ROOT so a
                // Turkish-locale host cannot map 'i' outside A-F and fail the CHECK.
                .addValue(
                        "clsbox",
                        requiredText(record.clsbox(), "clsbox").toUpperCase(Locale.ROOT))
                .addValue("ts_server", timestamp(ts.server(), "ts.server"))
                .addValue("ts_device", timestamp(ts.device(), "ts.device"))
                // Stored as received, never a discriminator (ADR-007 §4).
                .addValue("record_version", shortOrNull(record.version()), Types.SMALLINT)
                // Which of OUR schemas admitted the row, from the validator's result.
                .addValue("schema_version", validated.schemaVersion())
                .addValue("passthrough", passthroughJson(record), Types.VARCHAR)
                .addValue("data_extra", dataExtra, Types.VARCHAR)
                // Required members: the gate has proved presence and JSON type; the parse
                // here is the column's, and a mismatch is unmappable rather than silent.
                .addValue("memfree", WireNumbers.requiredInteger(data, "memfree"))
                .addValue("uptime", requiredDouble(data, "uptime"))
                .addValue("idletime", requiredDouble(data, "idletime"))
                .addValue("load1", requiredDouble(data, "load1"))
                .addValue("load5", requiredDouble(data, "load5"))
                .addValue("load15", requiredDouble(data, "load15"))
                .addValue("runable", requiredString(data, "runable"))
                // Optional members: NULL when the gateway did not send them.
                .addValue("temp", WireNumbers.optionalInteger(data, "temp"), Types.BIGINT)
                .addValue("rtt", WireNumbers.optionalInteger(data, "rtt"), Types.BIGINT)
                .addValue("wan_if", optionalString(data, "wan_if"), Types.VARCHAR)
                .addValue("rebootcause", optionalString(data, "rebootcause"), Types.VARCHAR)
                // The string "300": digits on the wire, BIGINT in the column, and the
                // range check happens BEFORE the narrowing conversion (STA-04C).
                .addValue(
                        "timespan",
                        WireNumbers.optionalDigitString(data, "timespan", WireNumbers.Sign.UNSIGNED),
                        Types.BIGINT);
        return parameters;
    }

    /**
     * The envelope members production sends that this system preserves and never
     * reads (ADR-003, ADR-007 §2, DL-146), in the wire's own shape and units — the same
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
            // Permanent: no redelivery changes the value (DL-168). The envelope refuses it at
            // Input now; this is the backstop for anything already queued.
            throw new PermanentFailureException("version does not fit the record_version column");
        }
        return version.shortValue();
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            // Permanent, as above (DL-168): the envelope requires a non-blank value now.
            throw new PermanentFailureException(field + " must be present");
        }
        return value;
    }


    private static double requiredDouble(JsonNode data, String field) {
        JsonNode value = data.get(field);
        if (value == null || value.isNull()) {
            throw new PermanentFailureException("data." + field + " must be present");
        }
        if (!value.isNumber()) {
            throw new PermanentFailureException("data." + field + " must be a number");
        }
        double parsed = value.doubleValue();
        if (!Double.isFinite(parsed)) {
            // JSON cannot spell NaN or Infinity, but Jackson will produce one from an
            // exponent too large for a double; the column would take it and mean nothing.
            throw new PermanentFailureException("data." + field + " is not a finite number");
        }
        return parsed;
    }

    private static String requiredString(JsonNode data, String field) {
        String value = optionalString(data, field);
        if (value == null) {
            throw new PermanentFailureException("data." + field + " must be present");
        }
        return value;
    }

    private static String optionalString(JsonNode data, String field) {
        JsonNode value = data.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new PermanentFailureException("data." + field + " must be a string");
        }
        return value.textValue();
    }


    private static Set<String> mappedFields() {
        List<String> all = new ArrayList<>(REQUIRED_FIELDS);
        all.addAll(OPTIONAL_FIELDS);
        return Set.copyOf(all);
    }
}
