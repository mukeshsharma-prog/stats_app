package com.zenner.datahub.stats.input.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.common.error.ProblemResponseSettings;
import com.zenner.datahub.stats.input.config.IngestEndpointProperties;
import com.zenner.datahub.stats.input.config.InputAuthProperties;
import com.zenner.datahub.stats.input.ingest.IngestMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Configuration;

/**
 * The guarantees of the authentication seam.
 *
 * <p>The mechanism changes what the filter <em>does</em>; what the filter does in
 * {@code oauth2} mode is {@link BearerTokenIngestFilterTest}. Asserted here are the seam and
 * its guards: the mode has no default, {@code none} announces itself, {@code oauth2} refuses
 * to start on incomplete settings, an unimplemented mode fails at startup rather than at
 * request time, and {@code prod} refuses {@code none} outright.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class InputAuthSeamTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(TestProperties.class, InputAuthConfiguration.class)
                    .withBean(IngestMetrics.class, () -> new IngestMetrics(new SimpleMeterRegistry()))
                    .withBean(ObjectMapper.class, ObjectMapper::new)
                    .withBean(
                            ProblemResponseSettings.class,
                            () -> ProblemResponseSettings.of("https://errors.test", Duration.ofSeconds(5)))
                    .withPropertyValues(
                            "stats.input.auth.required-role=stats.ingest",
                            "stats.input.endpoint.internal-path=/internal/stats",
                            "stats.input.endpoint.external-path=/external/stats",
                            "stats.input.endpoint.success-status=200");

    @Test
    @DisplayName("mode=none starts, registers the filter on both ingest paths, and WARNs")
    void noneModeStartsAndAnnouncesItself(CapturedOutput output) {
        runner.withPropertyValues("stats.input.auth.mode=none")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            FilterRegistrationBean<?> registration =
                                    context.getBean(FilterRegistrationBean.class);
                            // One mechanism, registered on both doors and nothing else.
                            assertThat(registration.getUrlPatterns())
                                    .containsExactlyInAnyOrder("/internal/stats", "/external/stats");
                            assertThat(registration.getOrder())
                                    .isEqualTo(UnauthenticatedIngestFilter.ORDER);
                            // The WARN is a courtesy, not the control — but a deferral nobody
                            // is reminded of is how a dev-only decision reaches production.
                            assertThat(output).contains("INGEST IS UNAUTHENTICATED");
                        });
    }

    @Test
    @DisplayName("an unimplemented mode fails at STARTUP, not per request")
    void unimplementedModeRefusesToStart() {
        // Accepting the mode and passing requests through would produce an endpoint that
        // reports itself as authenticated and is not — worse than none, which is honest.
        runner.withPropertyValues("stats.input.auth.mode=mtls")
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .hasMessageContaining("selected but not implemented"));
    }

    @Test
    @DisplayName("mode=oauth2 registers the bearer-token filter on both ingest paths and says so")
    void oauth2ModeRegistersTheBearerFilter(CapturedOutput output) {
        runner.withPropertyValues(oauth2("https://issuer.test/realms/statsapp", "stats-input"))
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            FilterRegistrationBean<?> registration =
                                    context.getBean(FilterRegistrationBean.class);
                            assertThat(registration.getFilter()).isInstanceOf(BearerTokenIngestFilter.class);
                            assertThat(registration.getUrlPatterns())
                                    .containsExactlyInAnyOrder("/internal/stats", "/external/stats");
                            assertThat(registration.getOrder()).isEqualTo(BearerTokenIngestFilter.ORDER);
                            assertThat(output).doesNotContain("INGEST IS UNAUTHENTICATED");
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer-uri", "audience"})
    @DisplayName("mode=oauth2 with an EMPTY issuer or audience refuses to start, naming the key")
    void oauth2ModeRefusesIncompleteSettings(String blankKey) {
        // Empty, not absent: `${STATS_INPUT_OAUTH2_AUDIENCE:}` binds "" when the variable is
        // missing, and an empty audience would accept a token for any client of the realm.
        String issuer = blankKey.equals("issuer-uri") ? "" : "https://issuer.test/realms/statsapp";
        String audience = blankKey.equals("audience") ? "" : "stats-input";
        runner.withPropertyValues(oauth2(issuer, audience))
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .hasMessageContaining("stats.input.auth.oauth2." + blankKey));
    }

    @Test
    @DisplayName("mode=oauth2 refuses a JWK set URI that is not http(s)")
    void oauth2ModeRefusesAMalformedJwkSetUri() {
        runner.withPropertyValues(oauth2("https://issuer.test/realms/statsapp", "stats-input"))
                .withPropertyValues("stats.input.auth.oauth2.jwk-set-uri=file:///etc/keys")
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .hasMessageContaining("stats.input.auth.oauth2.jwk-set-uri"));
    }

    @Test
    @DisplayName("the prod profile accepts an authenticated mode")
    void prodProfileAcceptsOauth2() {
        runner.withPropertyValues(oauth2("https://issuer.test/realms/statsapp", "stats-input"))
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("the prod profile REFUSES to start with mode=none")
    void prodProfileRefusesUnauthenticatedIngest() {
        runner.withPropertyValues("stats.input.auth.mode=none")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .hasMessageContaining("refused under the prod profile"));
    }

    @Test
    @DisplayName("an unset mode fails to start — there is no default, in code or anywhere else")
    void absentModeRefusesToStart() {
        // The whole point of the deferral: `none` has to be typed by a person.
        runner.run(context -> assertThat(context).hasFailed());
    }

    private static String[] oauth2(String issuer, String audience) {
        return new String[] {
            "stats.input.auth.mode=oauth2",
            "stats.input.auth.oauth2.issuer-uri=" + issuer,
            "stats.input.auth.oauth2.audience=" + audience
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({InputAuthProperties.class, IngestEndpointProperties.class})
    static class TestProperties {}
}
