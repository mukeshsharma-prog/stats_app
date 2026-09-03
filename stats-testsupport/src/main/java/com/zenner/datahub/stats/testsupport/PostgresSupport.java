package com.zenner.datahub.stats.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Shared PostgreSQL 17 Testcontainers support for persistence integration tests. */
@TestConfiguration(proxyBeanMethods = false)
public final class PostgresSupport {

    public static final String IMAGE = "postgres:17-alpine";

    private static final PostgreSQLContainer<?> CONTAINER =
            new PostgreSQLContainer<>(DockerImageName.parse(IMAGE));

    static {
        // Class initialization gives every consumer in this JVM the same real database.
        CONTAINER.start();
    }

    public PostgresSupport() {}

    /** Returns the JVM singleton for narrow connectivity and lifecycle assertions. */
    public static PostgreSQLContainer<?> container() {
        return CONTAINER;
    }

    /**
     * Lets Boot create JDBC connection details from the running container. Spring
     * contexts must not own or stop the JVM singleton.
     */
    @Bean(destroyMethod = "")
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        return CONTAINER;
    }
}
