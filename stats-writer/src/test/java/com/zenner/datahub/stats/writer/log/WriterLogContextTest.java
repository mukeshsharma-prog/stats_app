package com.zenner.datahub.stats.writer.log;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.common.model.StatsEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class WriterLogContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void restoresAmqpCorrelationBeforeParsingThenBindsRecordFields() throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setCorrelationId("trace-from-input");
        properties.setMessageId("uid-from-input");

        try (WriterLogContext.Scope ignored =
                WriterLogContext.open(new Message(new byte[0], properties), StatType.LORASTATS)) {
            assertThat(MDC.get(WriterLogContext.TRACE_ID)).isEqualTo("trace-from-input");
            assertThat(MDC.get(WriterLogContext.UID)).isEqualTo("uid-from-input");
            assertThat(MDC.get(WriterLogContext.TENANT)).isEqualTo("unknown");

            StatsEnvelope envelope = new ObjectMapper()
                    .readValue(
                            """
                            {"uid":"uid-1","tenant":"tenant-a","maintenant":"manager-a",\
                            "clsbox":"A1B2C3D4E5F6","type":"lorastats",\
                            "ts":{"server":1,"device":1},"data":{}}
                            """,
                            StatsEnvelope.class);
            WriterLogContext.bindRecord(envelope);
            WriterLogContext.errorCode("write_failed");

            assertThat(MDC.get(WriterLogContext.UID)).isEqualTo("uid-1");
            assertThat(MDC.get(WriterLogContext.STAT_TYPE)).isEqualTo("lorastats");
            assertThat(MDC.get(WriterLogContext.TENANT)).isEqualTo("tenant-a");
            assertThat(MDC.get(WriterLogContext.CLSBOX)).isEqualTo("A1B2C3D4E5F6");
            assertThat(MDC.get(WriterLogContext.ERROR_CODE)).isEqualTo("write_failed");
        }

        assertThat(MDC.getCopyOfContextMap()).isNull();
    }

    @Test
    void missingCorrelationGetsABoundedTraceAndUnknownUid() {
        try (WriterLogContext.Scope ignored =
                WriterLogContext.open(new Message(new byte[0]), StatType.LORASTATS)) {
            assertThat(MDC.get(WriterLogContext.TRACE_ID)).isNotBlank();
            assertThat(MDC.get(WriterLogContext.UID)).isEqualTo("unknown");
        }
    }

    @Test
    void bindingOutsideAConsumerScopeCannotLeakIntoAPooledThread() throws Exception {
        StatsEnvelope envelope = new ObjectMapper()
                .readValue(
                        """
                        {"uid":"leak","tenant":"leak","maintenant":"manager",\
                        "clsbox":"A1B2C3D4E5F6","type":"lorastats",\
                        "ts":{"server":1,"device":1},"data":{}}
                        """,
                        StatsEnvelope.class);

        WriterLogContext.bindRecord(envelope);
        WriterLogContext.errorCode("leak");

        assertThat(MDC.getCopyOfContextMap()).isNull();
    }
}
