package io.conddo.api.publicapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.domain.TenantSite;
import io.conddo.core.service.TenantSiteService;
import io.conddo.core.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public-site request authenticator + rate limiter (WEBSITE_INTEGRATION_SPEC §3).
 * On every {@code /api/v1/public/{slug}/...} call:
 *
 * <ol>
 *   <li>Pull the slug from the path and the API key from
 *       {@code X-Conddo-Site-Key}.</li>
 *   <li>Resolve via {@link TenantSiteService#resolveBySubdomain} — bcrypt match
 *       + {@code is_active} + {@code qa_approved} in one call. Anything
 *       missing → 401.</li>
 *   <li>Token-bucket rate limit per resolved site. Configurable via
 *       {@code conddo.public-site.rate-limit-per-minute} (default 120). 429
 *       with {@code Retry-After} on overflow.</li>
 *   <li>Bind {@code TenantContext} so RLS scopes downstream queries.</li>
 *   <li>Stash {@link PublicSiteAuth} on the request so controllers can read
 *       the resolved tenant without re-doing the work.</li>
 * </ol>
 */
@Component
public class PublicSiteInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Conddo-Site-Key";
    private static final Logger log = LoggerFactory.getLogger(PublicSiteInterceptor.class);

    private final TenantSiteService siteService;
    private final ObjectMapper objectMapper;
    private final int requestsPerMinute;

    /** Per-site token buckets, keyed by site id. */
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public PublicSiteInterceptor(TenantSiteService siteService, ObjectMapper objectMapper,
                                 @Value("${conddo.public-site.rate-limit-per-minute:120}") int rpm) {
        this.siteService = siteService;
        this.objectMapper = objectMapper;
        this.requestsPerMinute = Math.max(1, rpm);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String slug = extractSlug(request.getRequestURI());
        if (slug == null) {
            return reject(response, HttpStatus.NOT_FOUND, "NOT_FOUND", "Public site path missing slug");
        }
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            return reject(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Missing " + HEADER + " header");
        }

        Optional<TenantSite> resolved = siteService.resolveBySubdomain(slug, key);
        if (resolved.isEmpty()) {
            // Single 401 covers wrong key, inactive site, unapproved site — anti-enumeration.
            return reject(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Invalid site or API key");
        }
        TenantSite site = resolved.get();

        TokenBucket bucket = buckets.computeIfAbsent(site.getId().toString(),
                k -> new TokenBucket(requestsPerMinute, Duration.ofMinutes(1)));
        if (!bucket.tryConsume()) {
            response.addHeader("Retry-After", "60");
            return reject(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many requests — try again in a minute");
        }

        TenantContext.set(site.getTenantId());
        request.setAttribute(PublicSiteAuth.REQUEST_ATTRIBUTE,
                new PublicSiteAuth(site.getTenantId(), slug, site));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        TenantContext.clear();
    }

    private boolean reject(HttpServletResponse response, HttpStatus status, String code, String message)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", Map.of("code", code, "message", message));
        objectMapper.writeValue(response.getWriter(), body);
        return false;
    }

    static String extractSlug(String path) {
        String prefix = "/api/v1/public/";
        if (path == null || !path.startsWith(prefix)) {
            return null;
        }
        String rest = path.substring(prefix.length());
        int slash = rest.indexOf('/');
        String slug = slash < 0 ? rest : rest.substring(0, slash);
        return slug.isBlank() ? null : slug;
    }

    /** Trivial token bucket — refill {@code capacity} tokens every {@code window}. */
    private static final class TokenBucket {
        private final int capacity;
        private final long windowNanos;
        private double tokens;
        private long lastRefillNanos;

        TokenBucket(int capacity, Duration window) {
            this.capacity = capacity;
            this.windowNanos = window.toNanos();
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryConsume() {
            long now = System.nanoTime();
            double refill = ((double) (now - lastRefillNanos) / windowNanos) * capacity;
            tokens = Math.min(capacity, tokens + refill);
            lastRefillNanos = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
