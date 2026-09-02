package com.zenner.datahub.stats.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.ConnectionFactory;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;

class RabbitSupportTest {

    @Test
    void usesTheExactComposeRabbitMq4ImageAndAcceptsARealClientConnection() throws Exception {
        RabbitMQContainer container = RabbitSupport.container();
        ConnectionFactory client = new ConnectionFactory();
        client.setUri(container.getAmqpUrl());

        assertThat(container.getDockerImageName()).isEqualTo(RabbitSupport.IMAGE);
        assertThat(container.isRunning()).isTrue();
        try (var connection = client.newConnection()) {
            assertThat(connection.isOpen()).isTrue();
        }
    }

    @Test
    void reusesOneRunningContainerAcrossIndependentSpringContexts() {
        RabbitMQContainer singleton = RabbitSupport.container();
        String containerId = singleton.getContainerId();

        try (var first = new AnnotationConfigApplicationContext(RabbitSupport.class)) {
            assertThat(first.getBean(RabbitMQContainer.class)).isSameAs(singleton);
        }
        assertThat(singleton.isRunning()).isTrue();

        try (var second = new AnnotationConfigApplicationContext(RabbitSupport.class)) {
            assertThat(second.getBean(RabbitMQContainer.class)).isSameAs(singleton);
            assertThat(second.getBean(RabbitMQContainer.class).getContainerId())
                    .isEqualTo(containerId);
        }
    }

    @Test
    void delegatesConnectionDetailsToBootInsteadOfDynamicProperties() {
        assertThat(Arrays.stream(RabbitSupport.class.getDeclaredMethods()))
                .noneMatch(method -> method.isAnnotationPresent(DynamicPropertySource.class));
    }
}
