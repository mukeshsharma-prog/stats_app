package com.zenner.datahub.stats.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ErrorCodeTest {

    @ParameterizedTest
    @CsvSource({
        "UNKNOWN_STAT_TYPE, unknown_stat_type, 'Unknown stat type', 400",
        "INVALID_PARAMETER, invalid_parameter, 'Invalid request parameter', 400",
        "INVALID_TIME_RANGE, invalid_time_range, 'Invalid time range', 400",
        "QUERY_WINDOW_EXCEEDED, query_window_exceeded, 'Query window exceeded', 400",
        "INVALID_CURSOR, invalid_cursor, 'Invalid cursor', 400",
        "UNAUTHORIZED, unauthorized, Unauthorized, 401",
        "FORBIDDEN, forbidden, Forbidden, 403",
        "RECORD_NOT_FOUND, record_not_found, 'Record not found', 404",
        "METHOD_NOT_ALLOWED, method_not_allowed, 'Method not allowed', 405",
        "SERVICE_UNAVAILABLE, service_unavailable, 'Service unavailable', 503"
    })
    void ownsItsWireCodeTitleStatusAndTypeUri(
            ErrorCode errorCode,
            String wireCode,
            String title,
            int status) {
        assertThat(errorCode.wireCode()).isEqualTo(wireCode);
        assertThat(errorCode.title()).isEqualTo(title);
        assertThat(errorCode.status()).isEqualTo(status);
        assertThat(errorCode.typeUri())
                .hasToString(ErrorCode.TYPE_URI_BASE + "/" + wireCode);
    }

    @Test
    void catalogueContainsTenUniqueWireCodes() {
        assertThat(ErrorCode.values()).hasSize(10);
        assertThat(Arrays.stream(ErrorCode.values())
                        .map(ErrorCode::wireCode)
                        .collect(Collectors.toSet()))
                .hasSize(10);
    }
}
