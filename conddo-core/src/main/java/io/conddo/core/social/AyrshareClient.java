package io.conddo.core.social;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Port for talking to Ayrshare — the unified social-posting gateway
 * (SOCIAL_AND_CREATIVE_SERVICES_SPEC §1). Real HTTP adapter lives in
 * {@code conddo-api} (mirrors the {@code PaymentsGateway} pattern).
 *
 * <p>Fail-safe: every method returns {@link Optional} or a structured
 * result. Network errors / 5xx / 4xx are caught and logged in the adapter —
 * the orchestrator never blows up when Ayrshare blips.
 *
 * <p>{@code profileKey} arguments are the plaintext Ayrshare-issued strings
 * (decrypted from {@link io.conddo.core.domain.TenantSocialProfile} by the
 * service before they enter this port).
 */
public interface AyrshareClient {

    /** Whether the client is configured (env vars set) — endpoints check before calling. */
    boolean isConfigured();

    /**
     * Create the tenant's User Profile on Ayrshare. Called once per tenant
     * on first connect. Returns Ayrshare's {@code profileKey} (plaintext;
     * caller encrypts before persisting).
     */
    Optional<ProfileCreate> createProfile(String title);

    /**
     * Hosted-connect URL for the tenant to authorise a provider. Ayrshare's
     * hosted dialog handles every provider's OAuth dance — we never touch a
     * Meta/LinkedIn/X token directly.
     */
    Optional<String> connectLink(String profileKey);

    /** Pull the current list of connected providers for a tenant ({@code /api/user}). */
    Optional<List<String>> listConnectedPlatforms(String profileKey);

    /** Disconnect (unlink) a single provider; leaves the others alone. */
    boolean disconnect(String profileKey, String provider);

    /**
     * Publish — immediately if {@code scheduleDate} is null, scheduled if
     * provided (Strategy A). Returns one delivery row per requested platform
     * and Ayrshare's scheduledPostId when scheduled (for later cancellation).
     */
    Optional<PostDispatch> publish(String profileKey, String caption, List<String> platforms,
                                   List<String> mediaUrls, OffsetDateTime scheduleDate);

    /** Cancel a scheduled post on Ayrshare's side. */
    boolean cancelScheduledPost(String profileKey, String ayrsharePostId);

    /** Plaintext profileKey + the title Ayrshare echoes back. */
    record ProfileCreate(String profileKey, String title) {
    }

    /**
     * Ayrshare's response from {@code /api/post}: per-platform delivery status
     * plus the scheduledPostId (when scheduled). {@code targets} is keyed by
     * provider name ({@code "facebook"}, {@code "instagram"}, ...).
     */
    record PostDispatch(String ayrsharePostId, Map<String, TargetResult> targets) {
    }

    /** One platform's delivery shape — {@code published | failed | pending}. */
    record TargetResult(String status, String externalPostId, String errorMessage) {
    }
}
