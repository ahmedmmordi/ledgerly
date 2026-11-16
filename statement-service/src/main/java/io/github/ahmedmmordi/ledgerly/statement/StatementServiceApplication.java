package io.github.ahmedmmordi.ledgerly.statement;

import org.jspecify.annotations.NullMarked;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@NullMarked
@SpringBootApplication
public class StatementServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(StatementServiceApplication.class, args);
    }
}
