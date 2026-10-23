package com.zenner.datahub.stats.api.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zenner.datahub.stats.api.StatsApiApplication;
import com.zenner.datahub.stats.api.support.ApiDatabase;
import com.zenner.datahub.stats.api.support.TestJwks;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * An EMPTY issuer or audience stops the API, exactly as an
 * unset one does. Before this, {@code STATS_API_AUDIENCE=} bound to an empty list
 * and Boot validated no audience: a token minted for any client of the realm read data.
 */
class ResourceServerSettingsTest {

    @Test
    @DisplayName("a blank issuer, or an empty or blank audience, is refused by name")
    void blankValuesAreRefused() {
        assertThatIllegalStateException()
                .isThrownBy(() -> ResourceServerSettings.require(properties(null, List.of("stats-api"))))
                .withMessageContaining(ResourceServerSettings.ISSUER_KEY);
        assertThatIllegalStateException()
                .isThrownBy(() -> ResourceServerSettings.require(properties(" ", List.of("stats-api"))))
                .withMessageContaining(ResourceServerSettings.ISSUER_KEY);
        assertThatIllegalStateException()
                .isThrownBy(() -> ResourceServerSettings.require(properties(TestJwks.ISSUER, List.of())))
                .withMessageContaining(ResourceServerSettings.AUDIENCES_KEY);
        assertThatIllegalStateException()
                .isThrownBy(() -> ResourceServerSettings.require(properties(TestJwks.ISSUER, List.of("stats-api", " "))))
                .withMessageContaining(ResourceServerSettings.AUDIENCES_KEY);
        assertThatCode(() -> ResourceServerSettings.require(properties(TestJwks.ISSUER, List.of("stats-api"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the application does not start with STATS_API_AUDIENCE set to an empty value")
    void theApplicationRefusesToStartWithAnEmptyAudience() {
        // Command-line arguments, which outrank every properties file: builder defaults
        // would lose to api-test.properties and the test would prove nothing.
        String[] arguments = {
            "--spring.config.additional-location=classpath:api-test.properties",
            "--server.port=0",
            "--spring.datasource.url=" + ApiDatabase.jdbcUrl(),
            "--spring.datasource.username=" + ApiDatabase.READ_ONLY,
            "--spring.datasource.password=" + ApiDatabase.READ_ONLY_PASSWORD,
            "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + TestJwks.jwkSetUri(),
            "--spring.security.oauth2.resourceserver.jwt.audiences=",
        };
        SpringApplicationBuilder application = new SpringApplicationBuilder(StatsApiApplication.class);
        assertThatThrownBy(
                        () -> {
                            try (ConfigurableApplicationContext started = application.run(arguments)) {
                                // Reaching here is the failure: the context started.
                            }
                        })
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ResourceServerSettings.AUDIENCES_KEY);
    }

    private static OAuth2ResourceServerProperties properties(String issuer, List<String> audiences) {
        OAuth2ResourceServerProperties properties = new OAuth2ResourceServerProperties();
        properties.getJwt().setIssuerUri(issuer);
        properties.getJwt().setAudiences(audiences);
        return properties;
    }
}
