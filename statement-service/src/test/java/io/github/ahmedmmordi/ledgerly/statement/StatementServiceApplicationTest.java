package io.github.ahmedmmordi.ledgerly.statement;

import static org.assertj.core.api.Assertions.assertThat;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

@SpringBootTest
@Testcontainers
@DisplayName("StatementService Application Context & MongoDB Test")
class StatementServiceApplicationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void configureMongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> mongo.getConnectionString() + "/test");
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    @DisplayName("loads application context successfully with MongoDB container")
    void contextLoads() {
        assertThat(mongoTemplate).isNotNull();
    }

    @Test
    @DisplayName("executes ping command successfully on MongoDB database")
    void mongoConnectionIsHealthy() {
        Document pingResult = mongoTemplate.getDb().runCommand(new Document("ping", 1));
        assertThat(pingResult.getDouble("ok")).isEqualTo(1.0);
    }
}
