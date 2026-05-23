package io.conddo.core.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for authentication (PRD §6.2 / §12.1). Bound from
 * {@code conddo.security.auth.*}; enabled in {@code SecurityConfig}.
 *
 * @param refreshTokenTtl     lifetime of a refresh token (e.g. 30d)
 * @param lockoutThreshold    consecutive failed logins before the account locks (e.g. 5)
 * @param lockoutBaseDuration first lockout window; doubles per extra failure, capped (e.g. 15m)
 * @param cookieSecure        mark the refresh cookie {@code Secure} (true in prod; false for local http)
 * @param passwordResetTtl    lifetime of a password-reset token (e.g. 1h)
 */
@ConfigurationProperties(prefix = "conddo.security.auth")
public record AuthProperties(
        Duration refreshTokenTtl,
        int lockoutThreshold,
        Duration lockoutBaseDuration,
        boolean cookieSecure,
        Duration passwordResetTtl
) {
}
