package com.khmerdub.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static {
        try {
            Files.createDirectories(Path.of("target/test-storage"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        // Same major version as infra/docker-compose.yml.
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
    }
}
