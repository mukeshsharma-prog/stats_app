package com.zenner.datahub.stats.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.api.web.ProblemResponses;
import com.zenner.datahub.stats.api.web.ReadOnlyMethods;
import com.zenner.datahub.stats.api.web.TraceId;
import com.zenner.datahub.stats.common.error.ErrorCode;
import com.zenner.datahub.stats.common.error.ProblemResponseSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * The Keycloak resource server and who may read.
 *
 * <p><strong>What validates what.</strong> Boot's resource server validates the token's
 * signature, expiry, {@code iss} ({@code issuer-uri}) and {@code aud} ({@code audiences})
 * — any failure is a 401. {@link ReadAccess} then requires the {@code stats.readOnly}
 * realm role and a usable {@code tenant} claim on every {@code GET /v1/**} — either
 * missing is a 403. Every value is a property with no default in code; an unset one
 * stops the context rather than starting with a guessed posture — and so does a
 * blank issuer or audience ({@link ResourceServerSettings}), because an empty
 * audience list would otherwise switch the {@code aud} check off.</p>
 *
 * <p>Health probes disclose only UP/DOWN without authentication.
 * Prometheus also requires a token by default. The local Compose stack explicitly
 * enables {@code stats.security.prometheus-public-access} for its scraper; this
 * permits only the Prometheus endpoint, leaving stats reads and other actuator
 * endpoints subject to their existing authentication and authorization.</p>
 *
 * <p><strong>Filter order.</strong> {@link TraceId} runs first so every response —
 * a 401 included — carries {@code X-Trace-Id}; {@link ReadOnlyMethods} runs next, before
 * the security chain, so a non-GET is a 405 for every caller. CSRF is disabled:
 * this is a stateless bearer-token API with no cookie to forge, and with CSRF on an
 * authenticated POST would be a 403 instead of the contract's 405.</p>
 *
 * <p><strong>Unreachable signing keys are a 503, not a 401.</strong> When the keys
 * cannot be fetched, the decoder's verdict is not "bad token" but "could not check", and
 * Spring's default rethrows it — the servlet error page then answers 401, telling a caller
 * with a perfectly good token that its credential is wrong. The bearer filter's failure
 * handler answers 503 with {@code Retry-After} instead, as Input does.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
public class ApiSecurityConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(ApiSecurityConfiguration.class);

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            SecurityProperties properties,
            OAuth2ResourceServerProperties resourceServer,
            ProblemResponses problems)
            throws Exception {
        // Before the chain exists: an empty audience would otherwise disable the aud check.
        ResourceServerSettings.require(resourceServer);
        AuthenticationEntryPoint unauthorized = unauthorized(problems);
        AccessDeniedHandler forbidden = forbidden(problems);
        AuthenticationFailureHandler authenticationFailed = authenticationFailed(unauthorized, problems);
        http.authorizeHttpRequests(
                        authorize -> {
                            if (properties.prometheusPublicAccess()) {
                                authorize.requestMatchers(EndpointRequest.to("prometheus")).permitAll();
                            }
                            authorize
                                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class))
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/v1/**")
                                        .access(new ReadAccess(properties))
                                        .anyRequest()
                                        .authenticated();
                        })
                // Bearer tokens only: no form login and no HTTP basic, which Boot's
                // fallback would otherwise offer on a machine-to-machine API.
                .oauth2ResourceServer(
                        oauth2 ->
                                oauth2.jwt(Customizer.withDefaults())
                                        .authenticationEntryPoint(unauthorized)
                                        .accessDeniedHandler(forbidden)
                                        .withObjectPostProcessor(
                                                new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                                                    @Override
                                                    public <O extends BearerTokenAuthenticationFilter> O postProcess(
                                                            O filter) {
                                                        filter.setAuthenticationFailureHandler(authenticationFailed);
                                                        return filter;
                                                    }
                                                }))
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(unauthorized)
                                        .accessDeniedHandler(forbidden))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    public ProblemResponses problemResponses(ObjectMapper objectMapper, ProblemResponseSettings settings) {
        return new ProblemResponses(objectMapper, settings);
    }

    @Bean
    public FilterRegistrationBean<TraceId> traceIdFilter() {
        FilterRegistrationBean<TraceId> registration = new FilterRegistrationBean<>(new TraceId());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ReadOnlyMethods> readOnlyMethodsFilter(ProblemResponses problems) {
        FilterRegistrationBean<ReadOnlyMethods> registration =
                new FilterRegistrationBean<>(new ReadOnlyMethods(problems));
        // After TraceId, before Spring Security's chain (DEFAULT_FILTER_ORDER = -100).
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /**
     * 401 with the RFC 6750 {@code WWW-Authenticate} challenge Spring computes, and a
     * problem body. The challenge may say why (expired, wrong issuer); the body never
     * echoes the token.
     */
    private static AuthenticationEntryPoint unauthorized(ProblemResponses problems) {
        BearerTokenAuthenticationEntryPoint challenge = new BearerTokenAuthenticationEntryPoint();
        return (request, response, failure) -> {
            challenge.commence(request, response, failure);
            problems.write(request, response, ErrorCode.UNAUTHORIZED, "A valid bearer token is required.");
        };
    }

    /**
     * A token that could not be checked — the signing keys are unreachable — is 503 with
     * {@code Retry-After}; every other authentication failure is the ordinary 401. Only the
     * exception's type is logged: a decoder message can quote the token.
     */
    private static AuthenticationFailureHandler authenticationFailed(
            AuthenticationEntryPoint unauthorized, ProblemResponses problems) {
        return (request, response, failure) -> {
            if (!(failure instanceof AuthenticationServiceException)) {
                unauthorized.commence(request, response, failure);
                return;
            }
            LOG.atWarn()
                    .addKeyValue("event", "token_verification_unavailable")
                    .addKeyValue(
                            "exceptionType",
                            (failure.getCause() == null ? failure : failure.getCause()).getClass().getName())
                    .log("Read not served: the token could not be verified because the signing keys are unavailable");
            problems.write(
                    request,
                    response,
                    ErrorCode.SERVICE_UNAVAILABLE,
                    "The token could not be verified right now. Retry after the indicated interval.");
        };
    }

    private static AccessDeniedHandler forbidden(ProblemResponses problems) {
        return (request, response, denied) ->
                problems.write(
                        request,
                        response,
                        ErrorCode.FORBIDDEN,
                        "The token does not grant the required role or carries no usable tenant claim.");
    }
}
