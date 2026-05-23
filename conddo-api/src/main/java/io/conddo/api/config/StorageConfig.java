package io.conddo.api.config;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the MinIO client. Construction is lazy/connectionless — the client only
 * talks to MinIO when actually used — so this is safe to create at startup even
 * if the object store is briefly unavailable.
 */
@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class StorageConfig {

    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }
}
