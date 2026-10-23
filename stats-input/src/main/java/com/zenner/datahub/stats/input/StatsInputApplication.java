package com.zenner.datahub.stats.input;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Module 1 — Input Module.
 *
 * <p>Accepts statistics pushed from the Heimdal Core over HTTP, validates them
 * against the schema for their stat type, and publishes them to the
 * {@code app_stats_input} topic exchange using the stat type as routing key.
 *
 * <p>This module never touches PostgreSQL and returns 2xx only after a
 * RabbitMQ publisher confirm.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class StatsInputApplication {

    public static void main(String[] args) {
        SpringApplication.run(StatsInputApplication.class, args);
    }
}
