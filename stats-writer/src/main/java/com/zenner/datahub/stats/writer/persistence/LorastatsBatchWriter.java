package com.zenner.datahub.stats.writer.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.common.model.StatsEnvelope;
import com.zenner.datahub.stats.writer.consumer.LorastatsConsumerDescriptor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Individual, idempotent lorastats inserts behind the T-021 list contract. */
@Component
public final class LorastatsBatchWriter implements StatsBatchWriter {

    public static final String DUPLICATE_METRIC = "records_duplicate_suppressed";
    private static final short SCHEMA_VERSION = 1;

    private static final StatType TYPE = LorastatsConsumerDescriptor.TYPE;
    private static final String INSERT_SQL =
            """
            INSERT INTO %s (
                uid, tenant, maintenant, clsbox, ts_server, ts_device,
                schema_version, rx_nr_pkts, rx_crc_missing, rx_crc_bad,
                rx_short_pkt, tx_success, tx_failed, tx_peek, tx_peek_index_zero,
                tx_override, tx_busy, tx_dequeue_error, tx_type_beacon,
                chip_comm_error, util_fopen_failed, fsk_received, rx_type_arol,
                timespan
            ) VALUES (
                :uid, :tenant, :maintenant, :clsbox, :ts_server, :ts_device,
                :schema_version, :rx_nr_pkts, :rx_crc_missing, :rx_crc_bad,
                :rx_short_pkt, :tx_success, :tx_failed, :tx_peek, :tx_peek_index_zero,
                :tx_override, :tx_busy, :tx_dequeue_error, :tx_type_beacon,
                :chip_comm_error, :util_fopen_failed, :fsk_received, :rx_type_arol,
                :timespan
            )
            ON CONFLICT (ts_device, uid) DO NOTHING
            """.formatted(TYPE.wireName());

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final WriterBatchProperties properties;
    private final Counter duplicates;

    public LorastatsBatchWriter(
            NamedParameterJdbcTemplate jdbc,
            TransactionTemplate transactions,
            WriterBatchProperties properties,
            MeterRegistry meterRegistry) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.properties = Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.duplicates =
                Counter.builder(DUPLICATE_METRIC)
                        .description("Lorastats rows suppressed by the idempotency key")
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

    private static MapSqlParameterSource parameters(StatsEnvelope record) {
        if (record.type() != TYPE) {
            throw new IllegalArgumentException("record type must be " + TYPE.wireName());
        }
        Objects.requireNonNull(record.ts(), "record.ts");
        JsonNode data = Objects.requireNonNull(record.data(), "record.data");

        return new MapSqlParameterSource()
                .addValue("uid", requiredText(record.uid(), "uid"))
                .addValue("tenant", requiredText(record.tenant(), "tenant"))
                .addValue("maintenant", requiredText(record.maintenant(), "maintenant"))
                .addValue("clsbox", requiredText(record.clsbox(), "clsbox"))
                .addValue(
                        "ts_server",
                        Timestamp.from(Objects.requireNonNull(record.ts().server(), "ts.server")))
                .addValue(
                        "ts_device",
                        Timestamp.from(Objects.requireNonNull(record.ts().device(), "ts.device")))
                // The only lorastats schema selected by this v1 writer is lorastats-v1.
                // T-057 will make that selection explicit through the validation gate.
                .addValue("schema_version", SCHEMA_VERSION)
                .addValue("rx_nr_pkts", requiredLong(data, "rx_nr_pkts"))
                .addValue("rx_crc_missing", requiredLong(data, "rx_crc_missing"))
                .addValue("rx_crc_bad", requiredLong(data, "rx_crc_bad"))
                .addValue("rx_short_pkt", optionalLong(data, "rx_short_pkt"))
                .addValue("tx_success", requiredLong(data, "tx_success"))
                .addValue("tx_failed", requiredLong(data, "tx_failed"))
                .addValue("tx_peek", requiredLong(data, "tx_peek"))
                .addValue("tx_peek_index_zero", requiredLong(data, "tx_peek_index_zero"))
                .addValue("tx_override", requiredLong(data, "tx_override"))
                .addValue("tx_busy", requiredLong(data, "tx_busy"))
                .addValue("tx_dequeue_error", requiredLong(data, "tx_dequeue_error"))
                .addValue("tx_type_beacon", requiredLong(data, "tx_type_beacon"))
                .addValue("chip_comm_error", requiredLong(data, "chip_comm_error"))
                .addValue("util_fopen_failed", requiredLong(data, "util_fopen_failed"))
                .addValue("fsk_received", optionalLong(data, "fsk_received"))
                .addValue("rx_type_arol", optionalLong(data, "rx_type_arol"))
                .addValue("timespan", optionalLong(data, "timespan"));
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
