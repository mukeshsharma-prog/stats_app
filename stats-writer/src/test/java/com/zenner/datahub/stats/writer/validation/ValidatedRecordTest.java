package com.zenner.datahub.stats.writer.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.common.model.StatsEnvelope;
import com.zenner.datahub.stats.common.model.Timestamps;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValidatedRecordTest {

    private static final StatsEnvelope ENVELOPE =
            new StatsEnvelope(
                    "CLSCON-1",
                    "tenant-a",
                    "management-parent",
                    "A1B2C3D4E5F6",
                    StatType.LORASTATS,
                    1,
                    new Timestamps(Instant.EPOCH, Instant.EPOCH, null, null, null, null),
                    null,
                    null,
                    null);

    @Test
    void readsTheSchemaLabelTheValidatorReturned() {
        assertThat(ValidatedRecord.of(ENVELOPE, "v1").schemaVersion()).isEqualTo((short) 1);
        assertThat(ValidatedRecord.of(ENVELOPE, "v12").schemaVersion()).isEqualTo((short) 12);
    }

    /**
     * A silently defaulted schema label is a row that claims to have been admitted by a
     * contract nobody checked — invisible in the data and wrong forever. The design wants
     * the column to name the schema that actually admitted the record, so an unparseable
     * label has to stop the write rather than pick a number.
     */
    @ParameterizedTest
    @ValueSource(strings = {"1", "", "v", "va", "v1.1", "V1", "latest", "v99999"})
    void refusesALabelItCannotRead(String label) {
        assertThatThrownBy(() -> ValidatedRecord.of(ENVELOPE, label))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requiresAnEnvelopeAndAPositiveSchemaVersion() {
        assertThatThrownBy(() -> new ValidatedRecord(null, (short) 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ValidatedRecord(ENVELOPE, (short) 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
