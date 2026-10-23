package com.zenner.datahub.stats.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Module 3 — Stats API.
 *
 * <p>Read-only, versioned, Keycloak-secured REST endpoints.
 * GET only — no write verb is exposed, ever. Every query is scoped to the
 * {@code tenant} derived from the validated token claim.
 */
@SpringBootApplication
public class StatsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(StatsApiApplication.class, args);
    }
}
