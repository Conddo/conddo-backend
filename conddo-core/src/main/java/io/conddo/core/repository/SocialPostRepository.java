package io.conddo.core.repository;

import io.conddo.core.domain.SocialPost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * RLS-scoped. The webhook reconcile path uses {@link #findByAyrsharePostId}
 * inside a cross-tenant carve-out — the webhook payload identifies the post
 * by Ayrshare's id, not by tenant.
 */
public interface SocialPostRepository extends JpaRepository<SocialPost, UUID> {

    List<SocialPost> findByStatusInOrderByScheduledAtAsc(List<String> statuses);

    /**
     * Tenant feed for the dashboard composer — RLS-scoped. {@code status} is
     * optional ({@code null} = any).
     */
    List<SocialPost> findByScheduledAtBetweenOrderByScheduledAtDesc(
            OffsetDateTime from, OffsetDateTime to);

    /** Webhook reconcile lookup — call inside the cross-tenant carve-out. */
    Optional<SocialPost> findByAyrsharePostId(String ayrsharePostId);
}
