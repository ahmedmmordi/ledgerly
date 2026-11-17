package io.github.ahmedmmordi.ledgerly.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("API Gateway Application Context Test")
class ApiGatewayApplicationTest {

    @Test
    @DisplayName("Should successfully load the application context")
    void contextLoads() {
    }
}
