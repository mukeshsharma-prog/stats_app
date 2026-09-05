package com.zenner.datahub.stats.input.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.input.config.IngestEndpointProperties;
import com.zenner.datahub.stats.input.config.InputAuthProperties;
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
 * The four things T-017 owes, none of which is an authentication mechanism (Q8a).
 *
 * <p>Q8a-2 will decide mTLS or OAuth2 and change what the filter <em>does</em>. What is
 * asserted here is the seam and its guards, and those do not change with the answer:
 * the mode has no default, {@code none} announces itself, an unimplemented mode fails at
 * startup rather than at request time, and {@code prod} refuses {@code none} outright.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class InputAuthSeamTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(TestProperties.class, InputAuthConfiguration.class)
                    .withPropertyValues(
                            "stats.input.auth.required-role=stats.ingest",
                            "stats.input.endpoint.path=/stats",
                            "stats.input.endpoint.success-status=200");

    @Test
    @DisplayName("mode=none starts, registers the filter on the ingest path, and WARNs")
    void noneModeStartsAndAnnouncesItself(CapturedOutput output) {
        runner.withPropertyValues("stats.input.auth.mode=none")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            FilterRegistrationBean<?> registration =
                                    context.getBean(FilterRegistrationBean.class);
                            assertThat(registration.getUrlPatterns()).containsExactly("/stats");
                            assertThat(registration.getOrder())
                                    .isEqualTo(UnauthenticatedIngestFilter.ORDER);
                            // The WARN is a courtesy, not the control — but a deferral nobody
                            // is reminded of is how a dev-only decision reaches production.
                            assertThat(output).contains("INGEST IS UNAUTHENTICATED");
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"mtls", "oauth2"})
    @DisplayName("an unimplemented mode fails at STARTUP, not per request")
    void unimplementedModesRefuseToStart(String mode) {
        // Accepting the mode and passing requests through would produce an endpoint that
        // reports itself as authenticated and is not — worse than none, which is honest.
        runner.withPropertyValues("stats.input.auth.mode=" + mode)
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .hasMessageContaining("selected but not implemented"));
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

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({InputAuthProperties.class, IngestEndpointProperties.class})
    static class TestProperties {}
}
