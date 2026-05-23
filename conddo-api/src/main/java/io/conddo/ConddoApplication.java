package io.conddo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Conddo.io application entry point. Lives in the {@code io.conddo} root
 * package so component, entity and repository scanning covers both this module
 * and {@code conddo-core} ({@code io.conddo.core.*}).
 */
@SpringBootApplication
public class ConddoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConddoApplication.class, args);
    }
}
