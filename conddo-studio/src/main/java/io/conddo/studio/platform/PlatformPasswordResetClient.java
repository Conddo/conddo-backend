package io.conddo.studio.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Calls the platform's existing public {@code /auth/forgot-password} endpoint
 * to issue a password reset (§23.3). Studio doesn't have access to the
 * platform's {@code PasswordResetService} directly (it's in conddo-core), and
 * duplicating the token-issuing + email-templating logic here would diverge
 * from the platform's flow. HTTP back-call keeps a single source of truth.
 *
 * <p>Configured via {@code studio.platform.api-base-url}. When blank, this
 * adapter logs at WARN and returns {@code false} — the controller surfaces
 * that as 502 PLATFORM_API_UNAVAILABLE rather than a 500.
 */
@Component
public class PlatformPasswordResetClient {

    private static final Logger log = LoggerFactory.getLogger(PlatformPasswordResetClient.class);

    private final RestClient restClient;
    private final boolean configured;

    public PlatformPasswordResetClient(
            @Value("${studio.platform.api-base-url:}") String baseUrl,
            RestClient.Builder builder) {
        this.configured = baseUrl != null && !baseUrl.isBlank();
        this.restClient = configured ? builder.baseUrl(baseUrl.trim()).build() : null;
        if (!configured) {
            log.warn("Platform password-reset client is dormant "
                    + "(studio.platform.api-base-url not set) — reset endpoints will 502");
        }
    }

    public boolean isConfigured() {
        return configured;
    }

    /**
     * Triggers a password-reset email for the (tenantSlug, email) pair via the
     * platform. Returns {@code true} on a 2xx response; logs and returns
     * {@code false} on any HTTP / transport failure so the caller can emit a
     * 502 rather than crashing.
     */
    public boolean requestReset(String tenantSlug, String email) {
        if (!configured) {
            return false;
        }
        try {
            restClient.post()
                    .uri("/auth/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("tenantSlug", tenantSlug, "email", email))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException ex) {
            log.error("Platform /auth/forgot-password failed for {} / {}: {}",
                    tenantSlug, email, ex.getMessage());
            return false;
        }
    }
}
