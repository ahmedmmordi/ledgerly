package io.github.ahmedmmordi.ledgerly.statement;

import org.jspecify.annotations.NullMarked;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

@NullMarked
@EnableMongoAuditing
@SpringBootApplication(scanBasePackages = { "io.github.ahmedmmordi.ledgerly.statement",
        "io.github.ahmedmmordi.ledgerly.common.filter" })
public class StatementServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(StatementServiceApplication.class, args);
    }
}
