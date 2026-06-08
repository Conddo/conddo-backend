package io.conddo.core.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies the JWTs end-customers carry when interacting with a
 * merchant's public pharmacy website (PHARMACY_PUBLIC_API_SPEC §2). HMAC-
 * SHA256 with a shared secret ({@code CONDDO_CUSTOMER_JWT_SECRET}) — distinct
 * from the platform's RSA-signed tenant/staff tokens to keep the two trust
 * domains separate.
 *
 * <p>Each token carries {@code sub} (customer id), {@code tenant_id} (so
 * downstream services can bind RLS), and {@code role="CUSTOMER"} for
 * authorization rules.
 *
 * <p>Plain class (no Spring annotations); {@code conddo-api} constructs the
 * bean from the configured secret.
 */
public class CustomerJwtService {

    public static final String CLAIM_TENANT_ID = "tenant_id";
    public static final String CLAIM_ROLE = "role";
    public static final String ROLE_CUSTOMER = "CUSTOMER";
    public static final String ISSUER = "conddo:customer";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration ttl;

    public CustomerJwtService(String hmacSecret, Duration ttl) {
        if (hmacSecret == null || hmacSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(
                    "Customer JWT secret must be at least 32 bytes (256 bits) of entropy");
        }
        byte[] keyBytes = hmacSecret.getBytes(StandardCharsets.UTF_8);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "HmacSHA256");
        // NimbusJwtEncoder picks the key by matching `alg` on the JWS header
        // against jwk.alg — set HS256 explicitly so the encode-side selection
        // doesn't reject our key.
        OctetSequenceKey jwk = new OctetSequenceKey.Builder(keyBytes)
                .algorithm(JWSAlgorithm.HS256)
                .keyUse(KeyUse.SIGNATURE)
                .build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(jwk));
        this.encoder = new NimbusJwtEncoder(jwkSource);
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        this.ttl = ttl;
    }

    /** Issue a fresh JWT for the customer. */
    public String issue(UUID customerId, UUID tenantId, Clock clock) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .subject(customerId.toString())
                .claim(CLAIM_TENANT_ID, tenantId.toString())
                .claim(CLAIM_ROLE, ROLE_CUSTOMER)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * Verify the supplied token and return the decoded claims, or empty if
     * the signature is invalid / expired / malformed. Caller can then read
     * {@code sub} and {@code tenant_id}.
     */
    public Optional<Jwt> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(decoder.decode(token));
        } catch (JwtException ex) {
            return Optional.empty();
        }
    }

    public static UUID customerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public static UUID tenantId(Jwt jwt) {
        String raw = jwt.getClaimAsString(CLAIM_TENANT_ID);
        return UUID.fromString(raw);
    }
}
