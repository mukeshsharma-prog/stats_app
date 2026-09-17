package com.zenner.datahub.stats.api.security;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The API's security posture until T-038 lands the real resource server (ADR-006).
 *
 * <p><strong>Why this exists before T-038.</strong> {@code spring-boot-starter-security}
 * and the OAuth2 resource server are already on this module's classpath, and Boot's
 * fallback chain for that combination requires a bearer token on <em>every</em>
 * request — {@code /actuator/health/readiness} included. That is the endpoint
 * {@code docker/docker-compose.yml} health-checks, so the container could never become
 * healthy: an authenticated probe fails the deployment rather than protecting
 * anything (D13, D-10). DL-120 predicted exactly this for {@code stats-input} and kept
 * the starter out of that module; here the starter is wanted, so the posture has to be
 * written down instead of inherited.</p>
 *
 * <p><strong>What is carved out is the health endpoint and nothing else.</strong> The
 * probes disclose only UP/DOWN. {@code /actuator/prometheus} and {@code /actuator/info}
 * stay behind the token — scrape access is a T-038 decision, not a side effect of
 * making the healthcheck pass. Every other path is {@code authenticated()}, so the
 * fail-closed default is preserved; the {@code stats.readOnly} role and the
 * {@code tenant} claim are enforced by T-038, not here.</p>
 */
@Configuration(proxyBeanMethods = false)
public class ApiSecurityConfiguration {

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class))
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                // Bearer tokens only: no form login and no HTTP basic, which Boot's
                // fallback would otherwise offer on a machine-to-machine API.
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
