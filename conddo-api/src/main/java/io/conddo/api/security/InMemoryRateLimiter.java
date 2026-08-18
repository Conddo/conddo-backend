package io.conddo.api.security;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A small in-memory fixed-window rate limiter for the PUBLIC endpoints (§11.5)
 * and auth endpoints (brute-force defence). Single-instance only — fine for the
 * current single Render dyno; move to a shared store (Redis) when scaling
 * horizontally.
 *
 * <p>Defaults:
 * <ul>
 *   <li><b>20 requests per 60 seconds</b> for public API endpoints (key = client IP + bucket name).</li>
 *   <li>Auth endpoints get <b>10 per 60 seconds</b> — lower because the cost of a
 *       successful brute-force is higher than a public page scrape.</li>
 * </ul>
 */
@Component
public class InMemoryRateLimiter {

    private static final int MAX_REQUESTS = 20;
    private static final long WINDOW_MILLIS = 60_000L;

    /** Map of limiter-key → per-instance limits, overriding the global defaults. */
    private static final ConcurrentMap<String, LimitOverride> OVERRIDES = new ConcurrentHashMap<>();

    static {
        // Auth endpoints: lower ceiling for brute-force resistance.
        OVERRIDES.put("login", new LimitOverride(10, 60_000L));
        OVERRIDES.put("staff-login", new LimitOverride(10, 60_000L));
        OVERRIDES.put("public-login", new LimitOverride(10, 60_000L));
        OVERRIDES.put("register", new LimitOverride(10, 60_000L));
        OVERRIDES.put("password-reset", new LimitOverride(5, 60_000L));
    }

    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    /** Returns true if the call is allowed; false once the window's quota is spent. */
    public boolean tryAcquire(String key) {
        long now = System.currentTimeMillis();
        LimitOverride override = OVERRIDES.get(extractGroup(key));
        int maxRequests = override != null ? override.maxRequests : MAX_REQUESTS;
        long window = override != null ? override.windowMillis : WINDOW_MILLIS;

        Counter counter = counters.compute(key, (k, existing) -> {                if (existing == null || now - existing.windowStart >= window) {
                    return new Counter(now);
                }
            existing.count++;
            return existing;
        });
        return counter.count <= maxRequests;
    }

    /** Extracts the group name from a key like "1.2.3.4:login". */
    private static String extractGroup(String key) {
        if (key == null) return "";
        int colon = key.indexOf(':');
        return colon < 0 ? key : key.substring(colon + 1);
    }

    private static final class Counter {
        private final long windowStart;
        private int count;

        private Counter(long windowStart) {
            this.windowStart = windowStart;
            this.count = 1;
        }
    }

    /** Per-group limit overrides. */
    private record LimitOverride(int maxRequests, long windowMillis) {
    }
}
