package io.conddo.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Browser origins permitted to call the API (CORS).
 *
 * <p>Two binding points — both come from comma-separated env vars:
 * <ul>
 *   <li>{@code conddo.security.cors.allowed-origins} ({@code CONDDO_CORS_ALLOWED_ORIGINS})
 *       — exact origins, e.g. {@code http://localhost:3000,https://getconddo.com}.</li>
 *   <li>{@code conddo.security.cors.allowed-origin-patterns} ({@code CONDDO_CORS_ALLOWED_ORIGIN_PATTERNS})
 *       — Spring CORS patterns with wildcards, e.g.
 *       {@code https://*.getconddo.com,https://*.vercel.app}. Required for any
 *       deploy with preview URLs or multi-subdomain frontends.</li>
 * </ul>
 *
 * <p>Both forms work alongside {@code allowCredentials=true}; the wildcard
 * {@code "*"} is intentionally disallowed (browsers reject credentialled
 * requests with a wildcard ACAO header).
 */
@ConfigurationProperties(prefix = "conddo.security.cors")
public record CorsProperties(
        List<String> allowedOrigins,
        List<String> allowedOriginPatterns
) {
}
