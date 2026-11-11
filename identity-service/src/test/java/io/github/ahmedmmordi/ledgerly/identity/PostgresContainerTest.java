package io.github.ahmedmmordi.ledgerly.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PostgresContainerTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Test
    void postgresContainerShouldBeRunning() {
        assertThat(postgres.isRunning()).isTrue();
        assertThat(postgres.getJdbcUrl()).contains("jdbc:postgresql://");
        assertThat(postgres.getUsername()).isEqualTo("test");
        assertThat(postgres.getPassword()).isEqualTo("test");
    }
}
