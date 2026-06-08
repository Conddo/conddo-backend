package io.conddo.api.security;

import io.conddo.core.auth.CustomerJwtService;
import io.conddo.core.auth.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.converter.RsaKeyConverters;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;

/**
 * Loads the configured RSA key pair and exposes the {@link JwtService} that
 * signs and verifies access tokens. The keys are also reused by the resource
 * server's {@code JwtDecoder} in {@code SecurityConfig} (Slice 3) so there is a
 * single source of truth for verification.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    @Bean
    public RSAPublicKey jwtPublicKey(JwtProperties props) throws IOException {
        try (InputStream in = props.publicKey().getInputStream()) {
            return RsaKeyConverters.x509().convert(in);
        }
    }

    @Bean
    public RSAPrivateKey jwtPrivateKey(JwtProperties props) throws IOException {
        try (InputStream in = props.privateKey().getInputStream()) {
            return RsaKeyConverters.pkcs8().convert(in);
        }
    }

    @Bean
    public JwtService jwtService(RSAPublicKey jwtPublicKey, RSAPrivateKey jwtPrivateKey, JwtProperties props) {
        return new JwtService(jwtPublicKey, jwtPrivateKey, props.issuer(), props.accessTtl());
    }

    /**
     * Customer JWT service for the merchant-website-facing public surface
     * (PHARMACY_PUBLIC_API_SPEC §2). HMAC-SHA256 with the
     * {@code CONDDO_CUSTOMER_JWT_SECRET} env var. TTL defaults to 30 days
     * — customers expect a long-lived session on their pharmacy account,
     * the merchant can flip it shorter via {@code conddo.customer-jwt.ttl}.
     */
    @Bean
    public CustomerJwtService customerJwtService(
            @Value("${conddo.customer-jwt.secret:dev-customer-jwt-secret-at-least-32-bytes-long-pad}") String secret,
            @Value("${conddo.customer-jwt.ttl:30d}") Duration ttl) {
        return new CustomerJwtService(secret, ttl);
    }
}
