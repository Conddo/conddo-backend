package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.SocialPost;
import io.conddo.core.domain.SocialPostTarget;
import io.conddo.core.domain.TenantSocialProfile;
import io.conddo.core.repository.SocialPostRepository;
import io.conddo.core.repository.SocialPostTargetRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.TenantSocialProfileRepository;
import io.conddo.core.social.AyrshareClient;
import io.conddo.core.social.SocialTokenCipher;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates the Phase 1 social-marketing surface
 * (SOCIAL_AND_CREATIVE_SERVICES_SPEC §2-3). Sits between the controllers
 * and the {@link AyrshareClient} port, handling encryption of the
 * persisted profile key, the create-once profile lifecycle, the connect /
 * disconnect dance, post scheduling, and the webhook reconcile.
 *
 * <p>The Ayrshare {@code profileKey} is stored as ciphertext
 * ({@link SocialTokenCipher}); plaintext only exists in-memory for the
 * duration of a single Ayrshare call.
 */
@Service
public class SocialMarketingService {

    private static final Logger log = LoggerFactory.getLogger(SocialMarketingService.class);

    /** Refresh /api/user when we last saw it more than this ago (spec §3). */
    private static final Duration FRESH_WINDOW = Duration.ofMinutes(10);

    private final TenantSocialProfileRepository profileRepository;
    private final SocialPostRepository postRepository;
    private final SocialPostTargetRepository targetRepository;
    private final TenantRepository tenantRepository;
    private final AyrshareClient ayrshareClient;
    private final SocialTokenCipher cipher;
    private final TenantSession tenantSession;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    public SocialMarketingService(TenantSocialProfileRepository profileRepository,
                                  SocialPostRepository postRepository,
                                  SocialPostTargetRepository targetRepository,
                                  TenantRepository tenantRepository,
                                  AyrshareClient ayrshareClient,
                                  SocialTokenCipher cipher,
                                  TenantSession tenantSession,
                                  Clock clock) {
        this.profileRepository = profileRepository;
        this.postRepository = postRepository;
        this.targetRepository = targetRepository;
        this.tenantRepository = tenantRepository;
        this.ayrshareClient = ayrshareClient;
        this.cipher = cipher;
        this.tenantSession = tenantSession;
        this.clock = clock;
    }

    // ----- connect lifecycle -------------------------------------------------

    /**
     * Returns the Ayrshare hosted-connect URL. Creates the tenant's
     * Ayrshare User Profile on first call and persists the encrypted
     * profileKey. Re-callable — subsequent calls reuse the existing
     * profile and just request a fresh hosted URL.
     */
    @Transactional
    public ConnectLinkResult connectLink() {
        if (!ayrshareClient.isConfigured()) {
            throw new SocialUnconfiguredException(
                    "Ayrshare API key is not configured on this deployment.");
        }
        tenantSession.bind();
        UUID tenantId = TenantContext.require();
        TenantSocialProfile profile = profileRepository.findFirstByOrderByCreatedAtDesc()
                .orElseGet(() -> initialiseProfile(tenantId));
        String plaintextKey = cipher.decrypt(profile.getAyrshareProfileKey());
        String url = ayrshareClient.connectLink(plaintextKey)
                .orElseThrow(() -> new AyrshareUpstreamException(
                        "Ayrshare did not return a connect URL"));
        return new ConnectLinkResult(profile, url);
    }

    /**
     * Dashboard read of the current connection state. Refreshes from Ayrshare
     * if the cached snapshot is older than {@link #FRESH_WINDOW}.
     */
    @Transactional
    public Optional<TenantSocialProfile> currentProfile() {
        tenantSession.bind();
        Optional<TenantSocialProfile> existing = profileRepository.findFirstByOrderByCreatedAtDesc();
        if (existing.isEmpty()) {
            return existing;
        }
        TenantSocialProfile profile = existing.get();
        if (!isStale(profile)) {
            return existing;
        }
        if (!ayrshareClient.isConfigured()) {
            return existing;
        }
        String plaintextKey = cipher.decrypt(profile.getAyrshareProfileKey());
        ayrshareClient.listConnectedPlatforms(plaintextKey)
                .ifPresent(platforms -> profile.refreshConnectedPlatforms(
                        platforms, OffsetDateTime.now(clock)));
        return Optional.of(profileRepository.save(profile));
    }

    /** Disconnect a single provider; refreshes the snapshot after Ayrshare succeeds. */
    @Transactional
    public TenantSocialProfile disconnect(String provider) {
        if (!ayrshareClient.isConfigured()) {
            throw new SocialUnconfiguredException(
                    "Ayrshare API key is not configured on this deployment.");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        tenantSession.bind();
        TenantSocialProfile profile = profileRepository.findFirstByOrderByCreatedAtDesc()
                .orElseThrow(() -> new NotFoundException("No social profile registered yet"));
        String plaintextKey = cipher.decrypt(profile.getAyrshareProfileKey());
        if (!ayrshareClient.disconnect(plaintextKey, provider)) {
            throw new AyrshareUpstreamException(
                    "Ayrshare rejected the disconnect for " + provider);
        }
        // Re-pull the truth — Ayrshare is canonical, our snapshot follows.
        ayrshareClient.listConnectedPlatforms(plaintextKey)
                .ifPresentOrElse(
                        platforms -> profile.refreshConnectedPlatforms(platforms, OffsetDateTime.now(clock)),
                        () -> profile.removePlatform(provider));
        return profileRepository.save(profile);
    }

    // ----- posts -------------------------------------------------------------

    /** Tenant feed for the composer — RLS-scoped. */
    @Transactional(readOnly = true)
    public List<SocialPost> listPosts(OffsetDateTime from, OffsetDateTime to) {
        tenantSession.bind();
        OffsetDateTime start = from == null ? OffsetDateTime.now(clock).minusDays(30) : from;
        OffsetDateTime end = to == null ? OffsetDateTime.now(clock).plusDays(60) : to;
        return postRepository.findByScheduledAtBetweenOrderByScheduledAtDesc(start, end);
    }

    @Transactional(readOnly = true)
    public PostView get(UUID id) {
        tenantSession.bind();
        SocialPost post = postRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Post not found"));
        return new PostView(post, targetRepository.findByPostId(post.getId()));
    }

    /**
     * Schedule (or immediately publish if {@code scheduledAt} is within the
     * next minute, per spec §3). The post is persisted with status
     * {@code scheduled} / {@code publishing} BEFORE the Ayrshare call so a
     * provider blip is recoverable — the row stays in our database and a
     * retry endpoint can re-fire it.
     */
    @Transactional
    public PostView schedule(UUID authorUserId, String caption, List<Map<String, Object>> media,
                             OffsetDateTime scheduledAt, String timezone, List<String> platforms) {
        if (!ayrshareClient.isConfigured()) {
            throw new SocialUnconfiguredException(
                    "Ayrshare API key is not configured on this deployment.");
        }
        if (caption == null || caption.isBlank()) {
            throw new IllegalArgumentException("caption is required");
        }
        if (platforms == null || platforms.isEmpty()) {
            throw new IllegalArgumentException("at least one platform is required");
        }
        if (scheduledAt == null) {
            throw new IllegalArgumentException("scheduledAt is required");
        }
        tenantSession.bind();
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean immediate = !scheduledAt.isAfter(now.plusMinutes(1));
        Set<String> dedupPlatforms = new LinkedHashSet<>();
        platforms.forEach(p -> {
            if (p != null && !p.isBlank()) {
                dedupPlatforms.add(p.trim().toLowerCase());
            }
        });
        if (dedupPlatforms.isEmpty()) {
            throw new IllegalArgumentException("at least one platform is required");
        }

        SocialPost post = postRepository.save(new SocialPost(
                TenantContext.require(), authorUserId, caption, media, scheduledAt, timezone,
                immediate ? SocialPost.STATUS_PUBLISHING : SocialPost.STATUS_SCHEDULED));

        // One target row per channel — created as pending; Ayrshare reconciles.
        List<SocialPostTarget> targets = new ArrayList<>();
        for (String provider : dedupPlatforms) {
            targets.add(targetRepository.save(new SocialPostTarget(
                    TenantContext.require(), post.getId(), provider, SocialPostTarget.STATUS_PENDING)));
        }

        TenantSocialProfile profile = profileRepository.findFirstByOrderByCreatedAtDesc()
                .orElseThrow(() -> new NotFoundException(
                        "No social profile — connect a channel first."));
        String plaintextKey = cipher.decrypt(profile.getAyrshareProfileKey());
        List<String> mediaUrls = extractMediaUrls(media);

        Optional<AyrshareClient.PostDispatch> dispatch = ayrshareClient.publish(
                plaintextKey, caption, new ArrayList<>(dedupPlatforms), mediaUrls,
                immediate ? null : scheduledAt);

        if (dispatch.isEmpty()) {
            post.setStatus(SocialPost.STATUS_FAILED);
            targets.forEach(t -> t.markFailed("Ayrshare did not accept the post"));
            postRepository.save(post);
            targets.forEach(targetRepository::save);
            return new PostView(post, targets);
        }
        post.setAyrsharePostId(dispatch.get().ayrsharePostId());
        applyDispatchToTargets(targets, dispatch.get(), now);
        post.setStatus(deriveOverallStatus(targets, immediate));
        return new PostView(postRepository.save(post),
                targets.stream().map(targetRepository::save).toList());
    }

    @Transactional
    public PostView cancel(UUID id) {
        tenantSession.bind();
        SocialPost post = postRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Post not found"));
        if (!SocialPost.STATUS_SCHEDULED.equals(post.getStatus())) {
            throw new IllegalArgumentException(
                    "Only scheduled posts can be cancelled; current status: " + post.getStatus());
        }
        TenantSocialProfile profile = profileRepository.findFirstByOrderByCreatedAtDesc().orElse(null);
        if (profile != null && post.getAyrsharePostId() != null && ayrshareClient.isConfigured()) {
            String plaintextKey = cipher.decrypt(profile.getAyrshareProfileKey());
            ayrshareClient.cancelScheduledPost(plaintextKey, post.getAyrsharePostId());
        }
        post.setStatus(SocialPost.STATUS_FAILED);
        List<SocialPostTarget> targets = targetRepository.findByPostId(post.getId());
        targets.forEach(t -> t.markFailed("Cancelled by tenant"));
        targets.forEach(targetRepository::save);
        return new PostView(postRepository.save(post), targets);
    }

    // ----- webhook reconcile -------------------------------------------------

    /**
     * Apply an inbound Ayrshare webhook (post.published / post.failed /
     * account.disconnected). The webhook runs without a tenant context —
     * we look up the post by Ayrshare's id under a cross-tenant carve-out.
     * Idempotent: re-delivery of the same event just re-stamps the row.
     */
    @Transactional
    public void applyWebhook(String eventType, String ayrsharePostId, String provider,
                             String externalPostId, String errorMessage) {
        // Cross-tenant SELECT — the webhook payload is the only identifier.
        entityManager.createNativeQuery("SELECT set_config('app.public_resolver', 'true', true)")
                .getSingleResult();
        switch (eventType == null ? "" : eventType) {
            case "post.published" -> reconcilePublish(ayrsharePostId, provider, externalPostId, true, null);
            case "post.failed" -> reconcilePublish(ayrsharePostId, provider, externalPostId, false, errorMessage);
            case "account.disconnected" -> log.info(
                    "Ayrshare account.disconnected for provider {} (no row update needed; refreshed on next dashboard read)",
                    provider);
            default -> log.warn("Unhandled Ayrshare webhook event: {}", eventType);
        }
    }

    private void reconcilePublish(String ayrsharePostId, String provider, String externalPostId,
                                  boolean success, String errorMessage) {
        if (ayrsharePostId == null || provider == null) {
            return;
        }
        SocialPost post = postRepository.findByAyrsharePostId(ayrsharePostId).orElse(null);
        if (post == null) {
            log.warn("Ayrshare webhook referenced unknown post id {}", ayrsharePostId);
            return;
        }
        // Now that we know the tenant, bind RLS so subsequent writes work.
        TenantContext.set(post.getTenantId());
        tenantSession.bind();
        SocialPostTarget target = targetRepository
                .findByPostIdAndProvider(post.getId(), provider.toLowerCase())
                .orElseGet(() -> targetRepository.save(new SocialPostTarget(
                        post.getTenantId(), post.getId(), provider.toLowerCase(),
                        SocialPostTarget.STATUS_PENDING)));
        if (success) {
            target.markPublished(externalPostId, OffsetDateTime.now(clock));
        } else {
            target.markFailed(errorMessage);
        }
        targetRepository.save(target);
        List<SocialPostTarget> all = targetRepository.findByPostId(post.getId());
        post.setStatus(deriveOverallStatus(all, true));
        postRepository.save(post);
    }

    // ----- helpers -----------------------------------------------------------

    private TenantSocialProfile initialiseProfile(UUID tenantId) {
        String title = tenantRepository.findById(tenantId)
                .map(t -> t.getName())
                .orElse("Conddo Tenant");
        AyrshareClient.ProfileCreate created = ayrshareClient.createProfile(title)
                .orElseThrow(() -> new AyrshareUpstreamException(
                        "Ayrshare did not return a profileKey on create"));
        return profileRepository.save(new TenantSocialProfile(
                tenantId, cipher.encrypt(created.profileKey()),
                created.title() == null ? title : created.title()));
    }

    private boolean isStale(TenantSocialProfile profile) {
        OffsetDateTime last = profile.getLastSyncedAt();
        return last == null || Duration.between(last, OffsetDateTime.now(clock)).compareTo(FRESH_WINDOW) > 0;
    }

    private static List<String> extractMediaUrls(List<Map<String, Object>> media) {
        if (media == null || media.isEmpty()) {
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        for (Map<String, Object> m : media) {
            Object url = m.get("url");
            if (url != null) {
                urls.add(url.toString());
            }
        }
        return urls;
    }

    private void applyDispatchToTargets(List<SocialPostTarget> targets,
                                        AyrshareClient.PostDispatch dispatch,
                                        OffsetDateTime now) {
        for (SocialPostTarget target : targets) {
            AyrshareClient.TargetResult result = dispatch.targets().get(target.getProvider());
            if (result == null) {
                continue;
            }
            switch (result.status()) {
                case "published" -> target.markPublished(result.externalPostId(), now);
                case "failed" -> target.markFailed(result.errorMessage());
                default -> { /* stays pending; webhook will reconcile */ }
            }
        }
    }

    /**
     * Roll the per-target statuses up into the post status:
     * any published + nothing failed → published; all failed → failed;
     * any pending → publishing (immediate) / scheduled (deferred).
     */
    private static String deriveOverallStatus(List<SocialPostTarget> targets, boolean immediate) {
        boolean anyPublished = false;
        boolean anyPending = false;
        boolean anyFailed = false;
        for (SocialPostTarget t : targets) {
            switch (t.getStatus()) {
                case SocialPostTarget.STATUS_PUBLISHED -> anyPublished = true;
                case SocialPostTarget.STATUS_FAILED -> anyFailed = true;
                default -> anyPending = true;
            }
        }
        if (anyPublished && !anyPending && !anyFailed) {
            return SocialPost.STATUS_PUBLISHED;
        }
        if (anyFailed && !anyPublished && !anyPending) {
            return SocialPost.STATUS_FAILED;
        }
        return immediate ? SocialPost.STATUS_PUBLISHING : SocialPost.STATUS_SCHEDULED;
    }

    // ----- result records + exceptions --------------------------------------

    public record ConnectLinkResult(TenantSocialProfile profile, String connectUrl) {
    }

    public record PostView(SocialPost post, List<SocialPostTarget> targets) {
    }

    public static class SocialUnconfiguredException extends RuntimeException {
        public SocialUnconfiguredException(String msg) {
            super(msg);
        }
    }

    public static class AyrshareUpstreamException extends RuntimeException {
        public AyrshareUpstreamException(String msg) {
            super(msg);
        }
    }
}
