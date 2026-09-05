package com.zenner.datahub.stats.writer;

import com.zenner.datahub.stats.writer.persistence.WriterBatchProperties;
import com.zenner.datahub.stats.writer.retention.RetentionProperties;
import com.zenner.datahub.stats.writer.retry.WriterAmqpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Module 2 — Stats Writer (Statsstore).
 *
 * <p>Consumes from the per-type RabbitMQ queues and writes to PostgreSQL, one
 * table per stat type (STA-21). The only component with database write access
 * (STA-32), and the owner of retention (STA-23A).
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
    RetentionProperties.class,
    WriterBatchProperties.class,
    WriterAmqpProperties.class
})
public class StatsWriterApplication {

    public static void main(String[] args) {
        SpringApplication.run(StatsWriterApplication.class, args);
    }
}
