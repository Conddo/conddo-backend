package io.conddo.core.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerJwtServiceTest {

    private static final String SECRET = "test-customer-jwt-secret-at-least-32-bytes-long-PAD";

    @Test
    void issueAndVerifyRoundTrip() {
        CustomerJwtService svc = new CustomerJwtService(SECRET, Duration.ofMinutes(30));
        UUID customerId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        String token = svc.issue(customerId, tenantId, Clock.systemUTC());
        assertNotNull(token);
        assertTrue(token.split("\\.").length == 3, "token must be a 3-part JWT: " + token);

        Optional<Jwt> decoded = svc.verify(token);
        assertTrue(decoded.isPresent(), "verify must succeed for a freshly-issued token");
        assertEquals(customerId, CustomerJwtService.customerId(decoded.get()));
        assertEquals(tenantId, CustomerJwtService.tenantId(decoded.get()));
        assertEquals("CUSTOMER",
                decoded.get().getClaimAsString(CustomerJwtService.CLAIM_ROLE));
    }
}
