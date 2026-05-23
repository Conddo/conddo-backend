package io.conddo.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO connection settings (PRD §6.7 — self-hosted, S3-compatible storage).
 */
@ConfigurationProperties(prefix = "conddo.minio")
public record MinioProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket
) {
}
