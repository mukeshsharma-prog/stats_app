package com.zenner.datahub.stats.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/** Shared RabbitMQ 4 Testcontainers support for messaging integration tests. */
@TestConfiguration(proxyBeanMethods = false)
public final class RabbitSupport {

    public static final String IMAGE = "rabbitmq:4-management-alpine";

    private static final RabbitMQContainer CONTAINER =
            new RabbitMQContainer(DockerImageName.parse(IMAGE)) {
                // Boot's Testcontainers lifecycle processor calls close even when
                // destroyMethod is empty. A cached context must not stop services
                // still used by other contexts in the same test JVM. Ryuk owns cleanup.
                @Override
                public void close() {}
            };

    static {
        // Class initialization gives every consumer in this JVM the same broker.
        CONTAINER.start();
    }

    public RabbitSupport() {}

    /** Returns the JVM singleton for narrow connectivity and lifecycle assertions. */
    public static RabbitMQContainer container() {
        return CONTAINER;
    }

    /** Spring contexts consume the singleton but never own its lifecycle. */
    @Bean(destroyMethod = "")
    @ServiceConnection
    public RabbitMQContainer rabbitMqContainer() {
        return CONTAINER;
    }
}
