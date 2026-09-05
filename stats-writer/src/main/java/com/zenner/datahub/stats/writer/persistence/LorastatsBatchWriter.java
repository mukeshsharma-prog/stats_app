package com.zenner.datahub.stats.writer.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.common.model.StatsEnvelope;
import com.zenner.datahub.stats.writer.log.SafeFailure;
import com.zenner.datahub.stats.common.model.Timestamps;
import com.zenner.datahub.stats.writer.consumer.LorastatsConsumerDescriptor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
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
 */
@Component
public final class LorastatsBatchWriter implements StatsBatchWriter {

    public static final String DUPLICATE_METRIC = "records_duplicate_suppressed";
    public static final String UNMAPPED_DATA_METRIC = "records_unmapped_data_field";
    public static final String WRITE_FAILED_METRIC = "records_write_failed";

    private static final Logger LOGGER = LoggerFactory.getLogger(LorastatsBatchWriter.class);

    private static final short SCHEMA_VERSION = 1;

    private static final StatType TYPE = LorastatsConsumerDescriptor.TYPE;

    /**
     * Every {@code data} member lorastats-v1.schema.json names. Anything outside this
     * set has no typed column and is preserved in {@code data_extra} (D14b). Derived
     * from the schema by {@code LorastatsColumnMappingTest}, so a schema change that
     * adds a member cannot quietly start routing it to {@code data_extra}.
     */
    static final Set<String> MAPPED_DATA_FIELDS =
            Set.of(
                    "rx_nr_pkts",
                    "rx_crc_missing",
                    "rx_crc_bad",
                    "rx_short_pkt",
                    "tx_success",
                    "tx_failed",
                    "tx_peek",
                    "tx_peek_index_zero",
                    "tx_override",
                    "tx_busy",
                    "tx_dequeue_error",
                    "tx_type_beacon",
                    "chip_comm_error",
                    "util_fopen_failed",
                    "fsk_received",
                    "rx_type_arol",
                    "timespan");

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

    public LorastatsBatchWriter(
            NamedParameterJdbcTemplate jdbc,
            TransactionTemplate transactions,
            WriterBatchProperties properties,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
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
    }

    @Override
    public List<RecordWriteResult> write(List<StatsEnvelope> records) {
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
            try {
                int affectedRows = insertOne(records.get(index));
                if (affectedRows == 1) {
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
                LOGGER.error(
                        "lorastats write FAILED; delivery is not acknowledged and goes to "
                                + "the retry arm: uid={}, index={}, cause={}",
                        uidOf(records.get(index)),
                        index,
                        SafeFailure.describe(failure));
                results.add(RecordWriteResult.failed(index, failure));
            }
        }
        return List.copyOf(results);
    }

    private int insertOne(StatsEnvelope record) {
        Objects.requireNonNull(record, "record");
        Integer affectedRows =
                transactions.execute(status -> jdbc.update(INSERT_SQL, parameters(record)));
        if (affectedRows == null) {
            throw new IllegalStateException("Transaction completed without an insert result");
        }
        return affectedRows;
    }

    private MapSqlParameterSource parameters(StatsEnvelope record) {
        if (record.type() != TYPE) {
            throw new IllegalArgumentException("record type must be " + TYPE.wireName());
        }
        Timestamps ts = Objects.requireNonNull(record.ts(), "record.ts");
        JsonNode data = Objects.requireNonNull(record.data(), "record.data");

        String dataExtra = dataExtraJson(data);
        if (dataExtra != null) {
            unmappedDataFields.increment();
            LOGGER.debug(
                    "Preserving unmapped lorastats data members in data_extra: uid={}, members={}",
                    record.uid(),
                    unmappedFieldNames(data));
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
                // ADR-003 D7: which of OUR schemas validated the row. T-057 will set this
                // from the validator's own result; until that gate exists there is exactly
                // one lorastats schema in the catalogue and this writer implements it.
                .addValue("schema_version", SCHEMA_VERSION)
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

    private static final List<String> ORDERED_REQUIRED_FIELDS =
            List.of(
                    "rx_nr_pkts",
                    "rx_crc_missing",
                    "rx_crc_bad",
                    "tx_success",
                    "tx_failed",
                    "tx_peek",
                    "tx_peek_index_zero",
                    "tx_override",
                    "tx_busy",
                    "tx_dequeue_error",
                    "tx_type_beacon",
                    "chip_comm_error",
                    "util_fopen_failed");

    private static final List<String> ORDERED_OPTIONAL_FIELDS =
            List.of("rx_short_pkt", "fsk_received", "rx_type_arol", "timespan");

    /**
     * The envelope members production sends that this system preserves and never
     * reads: {@code ts.datastore}, {@code ts.converter}, {@code ts.expiresOn} and
     * {@code status} (ADR-003 "Required table and index direction", ADR-007 §2).
     *
     * <p>Written in the wire's own shape and units — epoch <em>seconds</em>, and
     * {@code expiresOn} as the ISO-8601 string production actually sends — so the
     * stored value reconstructs the received one. Returns {@code null} when the
     * record carries none of them, which is every fixture written before 29 Aug 2026.</p>
     */
    private String passthroughJson(StatsEnvelope record) {
        Timestamps ts = record.ts();
        ObjectNode passthrough = objectMapper.createObjectNode();

        ObjectNode timestamps = objectMapper.createObjectNode();
        putEpochSeconds(timestamps, "datastore", ts.datastore());
        putEpochSeconds(timestamps, "converter", ts.converter());
        if (ts.expiresOn() != null) {
            timestamps.put("expiresOn", ts.expiresOn());
        }
        if (!timestamps.isEmpty()) {
            passthrough.set("ts", timestamps);
        }
        if (record.status() != null) {
            passthrough.set("status", objectMapper.valueToTree(record.status()));
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

    private static String uidOf(StatsEnvelope record) {
        return record == null ? null : record.uid();
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
