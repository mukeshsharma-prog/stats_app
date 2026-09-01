package com.zenner.datahub.stats.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

class PostgresSupportTest {

    @Test
    void usesTheExactComposePostgreSql17ImageAndExecutesSqlAgainstARealDatabase()
            throws Exception {
        PostgreSQLContainer<?> container = PostgresSupport.container();

        assertThat(container.getDockerImageName()).isEqualTo(PostgresSupport.IMAGE);
        assertThat(container.isRunning()).isTrue();
        try (var connection = container.createConnection("");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void reusesOneRunningContainerAcrossIndependentSpringContexts() {
        PostgreSQLContainer<?> singleton = PostgresSupport.container();
        String containerId = singleton.getContainerId();

        try (var first = new AnnotationConfigApplicationContext(PostgresSupport.class)) {
            assertThat(first.getBean(PostgreSQLContainer.class)).isSameAs(singleton);
        }
        assertThat(singleton.isRunning()).isTrue();

        try (var second = new AnnotationConfigApplicationContext(PostgresSupport.class)) {
            assertThat(second.getBean(PostgreSQLContainer.class)).isSameAs(singleton);
            assertThat(second.getBean(PostgreSQLContainer.class).getContainerId())
                    .isEqualTo(containerId);
        }
    }

    @Test
    void delegatesConnectionDetailsToBootInsteadOfDynamicProperties() {
        assertThat(Arrays.stream(PostgresSupport.class.getDeclaredMethods()))
                .noneMatch(method -> method.isAnnotationPresent(DynamicPropertySource.class));
    }
}
