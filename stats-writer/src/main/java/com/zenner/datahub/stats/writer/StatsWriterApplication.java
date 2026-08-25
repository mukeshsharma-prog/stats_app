package com.zenner.datahub.stats.writer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Module 2 — Stats Writer (Statsstore).
 *
 * <p>Consumes from the per-type RabbitMQ queues and writes to PostgreSQL, one
 * table per stat type (STA-21). The only component with database write access
 * (STA-32), and the owner of retention (STA-23A).
 */
@SpringBootApplication
public class StatsWriterApplication {

    public static void main(String[] args) {
        SpringApplication.run(StatsWriterApplication.class, args);
    }
}
