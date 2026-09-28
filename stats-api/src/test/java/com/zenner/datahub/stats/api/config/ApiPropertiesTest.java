package com.zenner.datahub.stats.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.zenner.datahub.stats.api.security.SecurityProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fail closed: the paging, window, cursor and security settings have no defaults in code,
 * and an unset or unsafe one stops the context (T-038, T-040, T-042; ADR-006).
 */
class ApiPropertiesTest {

    private static final ApiProperties.Cursor CURSOR =
            new ApiProperties.Cursor("k".repeat(32), Duration.ofMinutes(15));

    @Test
    @DisplayName("every paging, window and cursor value is required")
    void everyValueIsRequired() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ApiProperties(null, 1000, Duration.ofHours(1), CURSOR))
                .withMessageContaining("stats.api.default-page-size");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ApiProperties(100, null, Duration.ofHours(1), CURSOR))
                .withMessageContaining("stats.api.max-page-size");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ApiProperties(100, 1000, null, CURSOR))
                .withMessageContaining("stats.api.max-query-window");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ApiProperties(100, 1000, Duration.ofHours(1), null));
    }

    @Test
    @DisplayName("configuration may lower the contract's 1000-row ceiling, never raise it")
    void pageSizesStayInsideTheContract() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ApiProperties(100, 1001, Duration.ofHours(1), CURSOR));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ApiProperties(501, 500, Duration.ofHours(1), CURSOR));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ApiProperties(100, 1000, Duration.ZERO, CURSOR));
    }

    @Test
    @DisplayName("the cursor key is required, at least 32 bytes, and never printed")
    void theCursorKeyIsRequiredLongAndRedacted() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ApiProperties.Cursor(null, Duration.ofMinutes(15)))
                .withMessageContaining("stats.api.cursor.signing-key");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ApiProperties.Cursor("short", Duration.ofMinutes(15)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ApiProperties.Cursor("k".repeat(32), Duration.ZERO));
        assertThat(CURSOR.toString()).doesNotContain("kkkk").contains("redacted");
    }

    @Test
    @DisplayName("the read role and the tenant claim are required")
    void securityValuesAreRequired() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SecurityProperties(null, "tenant"))
                .withMessageContaining("stats.security.read-role");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SecurityProperties("stats.readOnly", " "))
                .withMessageContaining("stats.security.tenant-claim");
    }
}
