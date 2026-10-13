package com.zenner.datahub.stats.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.api.support.ApiIntegrationTest;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every connection the API's pool hands out carries a server-side statement timeout
 * (DL-168), read back from PostgreSQL rather than from the property, so a typo in the
 * driver option cannot pass.
 */
class ReadStatementTimeoutIntegrationTest extends ApiIntegrationTest {

    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("the read pool's sessions run with statement_timeout = 10s")
    void everyReadIsBoundedByTheServer() {
        String timeout =
                new JdbcTemplate(dataSource).queryForObject("SHOW statement_timeout", String.class);

        assertThat(timeout).isEqualTo("10s");
    }
}
