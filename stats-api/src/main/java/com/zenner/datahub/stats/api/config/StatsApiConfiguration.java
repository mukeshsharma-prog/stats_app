package com.zenner.datahub.stats.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.api.cursor.CursorCodec;
import com.zenner.datahub.stats.api.query.LorastatsReader;
import com.zenner.datahub.stats.api.query.OmsstatsReader;
import com.zenner.datahub.stats.api.query.RecordAssembler;
import com.zenner.datahub.stats.api.query.StatReader;
import com.zenner.datahub.stats.api.query.StatReaders;
import com.zenner.datahub.stats.api.query.StatsRepository;
import com.zenner.datahub.stats.api.query.SysstatsReader;
import com.zenner.datahub.stats.api.web.StatsReadService;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the read path. Each {@link StatReader} is its own bean, so adding a stat type
 * adds a reader and touches no existing one (rule 6, STA-14A).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApiProperties.class)
public class StatsApiConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public StatReader lorastatsReader() {
        return new LorastatsReader();
    }

    @Bean
    public StatReader sysstatsReader() {
        return new SysstatsReader();
    }

    @Bean
    public StatReader omsstatsReader() {
        return new OmsstatsReader();
    }

    @Bean
    public StatReaders statReaders(List<StatReader> readers) {
        return new StatReaders(readers);
    }

    @Bean
    public StatsRepository statsRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        return new StatsRepository(jdbc, new RecordAssembler(objectMapper));
    }

    @Bean
    public CursorCodec cursorCodec(ApiProperties properties, Clock clock, ObjectMapper objectMapper) {
        return new CursorCodec(
                properties.cursor().signingKey(), properties.cursor().ttl(), clock, objectMapper);
    }

    @Bean
    public StatsReadService statsReadService(
            ApiProperties properties, StatReaders readers, StatsRepository repository, CursorCodec cursors) {
        return new StatsReadService(properties, readers, repository, cursors);
    }
}
