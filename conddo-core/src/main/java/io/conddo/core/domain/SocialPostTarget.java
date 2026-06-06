package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One target channel per {@link SocialPost} — the spec calls for cross-
 * posting (Facebook + Instagram + LinkedIn etc. from a single composer),
 * and Ayrshare returns one delivery result per channel which we mirror
 * here. {@code tenantId} is denormalised onto the row so RLS scopes the
 * table directly without a join through {@code social_posts}.
 */
@Entity
@Table(name = "social_post_targets")
public class SocialPostTarget {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_FAILED = "failed";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "post_id", nullable = false)
    private UUID postId;

    @Column(nullable = false)
    private String provider;

    @Column(name = "external_post_id")
    private String externalPostId;

    @Column(nullable = false)
    private String status;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected SocialPostTarget() {
    }

    public SocialPostTarget(UUID tenantId, UUID postId, String provider, String status) {
        this.tenantId = tenantId;
        this.postId = postId;
        this.provider = provider;
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getPostId() {
        return postId;
    }

    public String getProvider() {
        return provider;
    }

    public String getExternalPostId() {
        return externalPostId;
    }

    public String getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /** Mark this target delivered — called from the post.published webhook reconcile path. */
    public void markPublished(String externalPostId, OffsetDateTime at) {
        this.status = STATUS_PUBLISHED;
        this.externalPostId = externalPostId;
        this.publishedAt = at;
        this.errorMessage = null;
    }

    /** Mark this target failed — surfaces the Ayrshare error string for the FE. */
    public void markFailed(String reason) {
        this.status = STATUS_FAILED;
        this.errorMessage = reason;
    }
}
