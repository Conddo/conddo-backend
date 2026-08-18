package io.conddo.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * IP-based rate limiter for authentication endpoints to prevent brute-force
 * login attacks (PRD §6.2 hardening). Applies to {@code /auth/login},
 * {@code /auth/staff/login}, {@code /auth/register/*}, and
 * {@code /auth/forgot-password}.
 *
 * <p>A client IP that exceeds the limit (default 10 attempts per 60-second
 * sliding window) receives HTTP 429 with {@code Retry-After} and a JSON error
 * body. The filter runs early, before JWT processing and any DB work, so a
 * flooding attacker never reaches the controller.
 *
 * <p>The {@link InMemoryRateLimiter} is fine for single-instance Render dynos.
 * When scaling horizontally, swap to a Redis-backed limiter.
 *
 * <p>Long-lived port 0 / pod IP quirks: the IP is extracted from
 * {@code X-Forwarded-For} first (the real client behind Render's proxy),
 * falling back to {@code getRemoteAddr()}.
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);

    /** Paths subject to per-IP rate limiting. */
    private static final String[] PROTECTED_PATHS = {
            "/auth/login",
            "/auth/staff/login",
            "/auth/register/",
            "/auth/forgot-password",
            "/auth/reset-password",
    };

    /** One bucket per (IP + endpoint-group), e.g. "1.2.3.4:login". */
    private static final String BUCKET_SEPARATOR = ":";

    private final InMemoryRateLimiter rateLimiter;

    public AuthRateLimitFilter(InMemoryRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String method = request.getMethod();
        String path = request.getRequestURI();

        // Only rate-limit POST requests to auth endpoints
        if ("POST".equalsIgnoreCase(method) && isProtectedPath(path)) {
            String ip = clientIp(request);
            String bucketKey = ip + BUCKET_SEPARATOR + extractBucket(path);

            if (!rateLimiter.tryAcquire(bucketKey)) {
                log.warn("Rate limit exceeded for auth endpoint {} from IP {}", path, ip);
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                response.addHeader("Retry-After", "60");
                response.getWriter().write(
                        "{\"success\":false,\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests. Please wait before trying again.\"}}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private static boolean isProtectedPath(String path) {
        if (path == null) return false;
        // Match direct auth endpoints (/auth/login, /auth/staff/login, etc.)
        if (path.startsWith("/auth/") && path.length() > 6) {
            String suffix = path.substring(6);
            if (suffix.equals("login") || suffix.equals("staff/login")
                    || suffix.startsWith("register")
                    || suffix.equals("forgot-password")
                    || suffix.equals("reset-password")) {
                return true;
            }
        }
        // Match public API customer auth endpoints (/api/v1/public/{slug}/auth/login etc.)
        int publicAuthIdx = path.indexOf("/auth/");
        if (publicAuthIdx > 0 && path.startsWith("/api/v1/public/")) {
            String suffix = path.substring(publicAuthIdx + 6);
            if (suffix.equals("login") || suffix.startsWith("register")
                    || suffix.equals("forgot-password")
                    || suffix.equals("reset-password")) {
                return true;
            }
        }
        return false;
    }

    /** Groups endpoints so an attacker can't exhaust both quotas independently for the same IP. */
    private static String extractBucket(String path) {
        if (path.contains("/staff/")) return "staff-login";
        if (path.contains("/public/")) return "public-login";
        if (path.contains("/register")) return "register";
        if (path.contains("/forgot-password") || path.contains("/reset-password")) return "password-reset";
        return "login";
    }

    private static String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
