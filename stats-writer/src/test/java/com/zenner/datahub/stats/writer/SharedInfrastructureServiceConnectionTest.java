package com.zenner.datahub.stats.writer;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.Channel;
import com.zenner.datahub.stats.testsupport.PostgresSupport;
import com.zenner.datahub.stats.testsupport.RabbitSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

/** T-008 proof that the Writer consumes both shared service-connection fixtures. */
@SpringBootTest(
        classes = StatsWriterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "stats.writer.stat-type=netstats",
            "stats.retention.schedule=-",
            // These tests create and drop their own partitions on fixed far-future
            // days. The startup pre-creation is exercised by
            // PartitionBootstrapIntegrationTest, which leaves it on.
            "stats.retention.pre-create-on-startup=false"
        })
@Import({PostgresSupport.class, RabbitSupport.class})
class SharedInfrastructureServiceConnectionTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConnectionFactory rabbitConnectionFactory;

    @Test
    void bootWiresBothRealServicesFromTheSharedContainerBeans() throws Exception {
        assertThat(context.getBean(PostgreSQLContainer.class)).isSameAs(PostgresSupport.container());
        assertThat(context.getBean(RabbitMQContainer.class)).isSameAs(RabbitSupport.container());
        assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);

        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL())
                    .contains(PostgresSupport.container().getHost())
                    .contains(":" + PostgresSupport.container().getMappedPort(5432));
        }
        try (var connection = rabbitConnectionFactory.createConnection();
                Channel channel = connection.createChannel(false)) {
            assertThat(connection.isOpen()).isTrue();
            assertThat(channel.isOpen()).isTrue();
        }
    }

    @Test
    void connectionDetailsUseServiceConnectionInsteadOfDynamicPropertyBridges() {
        assertThat(SharedInfrastructureServiceConnectionTest.class.getDeclaredMethods())
                .noneMatch(method -> method.isAnnotationPresent(DynamicPropertySource.class));
    }
}
