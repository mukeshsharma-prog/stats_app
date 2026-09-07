package com.zenner.datahub.stats.input.log;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.common.error.ErrorCode;
import com.zenner.datahub.stats.common.model.StatType;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class IngestLogContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void startsWithBoundedPlaceholdersThenBindsOnlyAValidatedRecord() throws Exception {
        try (IngestLogContext.Scope ignored = IngestLogContext.openRequest()) {
            assertThat(MDC.get(IngestLogContext.TRACE_ID)).isNotBlank();
            assertThat(MDC.get(IngestLogContext.UID)).isEqualTo(IngestLogContext.UNKNOWN);
            assertThat(MDC.get(IngestLogContext.ERROR_CODE)).isEqualTo(IngestLogContext.NO_ERROR);

            IngestLogContext.bindValidatedRecord(
                    new ObjectMapper()
                            .readTree(
                                    """
                                    {"uid":"uid-1","tenant":"tenant-a",\
                                    "clsbox":"A1B2C3D4E5F6"}
                                    """),
                    StatType.LORASTATS);
            IngestLogContext.errorCode(ErrorCode.SERVICE_UNAVAILABLE);

            assertThat(MDC.get(IngestLogContext.UID)).isEqualTo("uid-1");
            assertThat(MDC.get(IngestLogContext.STAT_TYPE)).isEqualTo("lorastats");
            assertThat(MDC.get(IngestLogContext.TENANT)).isEqualTo("tenant-a");
            assertThat(MDC.get(IngestLogContext.CLSBOX)).isEqualTo("A1B2C3D4E5F6");
            assertThat(MDC.get(IngestLogContext.ERROR_CODE)).isEqualTo("service_unavailable");
        }
    }

    @Test
    void closingTheRequestRestoresOuterContextInsteadOfLeakingRecordFields() {
        MDC.clear();
        MDC.put("outer", "kept");

        try (IngestLogContext.Scope ignored = IngestLogContext.openRequest()) {
            assertThat(MDC.get("outer")).isNull();
            assertThat(MDC.get(IngestLogContext.UID)).isEqualTo(IngestLogContext.UNKNOWN);
        }

        assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("outer", "kept"));
    }

    @Test
    void bindingOutsideARequestScopeCannotLeakIntoAPooledThread() throws Exception {
        MDC.clear();
        IngestLogContext.bindValidatedRecord(
                new ObjectMapper()
                        .readTree("{\"uid\":\"leak\",\"tenant\":\"leak\",\"clsbox\":\"A1B2C3D4E5F6\"}"),
                StatType.LORASTATS);
        IngestLogContext.errorCode(ErrorCode.INVALID_PARAMETER);

        assertThat(MDC.getCopyOfContextMap()).isNull();
    }
}
