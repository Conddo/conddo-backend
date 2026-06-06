package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A merchant-authored post for cross-posting to multiple social channels
 * (SOCIAL_AND_CREATIVE_SERVICES_SPEC §3). The post itself is platform-agnostic;
 * each target channel lives on {@link SocialPostTarget}.
 *
 * <p>{@code status} lifecycle: {@code draft → scheduled → publishing →
 * published | failed}. Strategy A means Ayrshare owns the scheduler — we
 * store {@code ayrsharePostId} and reconcile on webhook callbacks.
 */
@Entity
@Table(name = "social_posts")
public class SocialPost {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_SCHEDULED = "scheduled";
    public static final String STATUS_PUBLISHING = "publishing";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_FAILED = "failed";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "author_user_id", nullable = false)
    private UUID authorUserId;

    @Column(nullable = false)
    private String caption;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "media")
    private List<Map<String, Object>> media;

    @Column(name = "scheduled_at", nullable = false)
    private OffsetDateTime scheduledAt;

    @Column(nullable = false)
    private String timezone = "Africa/Lagos";

    @Column(nullable = false)
    private String status;

    /** Ayrshare's scheduledPostId — needed for DELETE/cancellation calls. */
    @Column(name = "ayrshare_post_id")
    private String ayrsharePostId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected SocialPost() {
    }

    public SocialPost(UUID tenantId, UUID authorUserId, String caption,
                      List<Map<String, Object>> media, OffsetDateTime scheduledAt,
                      String timezone, String status) {
        this.tenantId = tenantId;
        this.authorUserId = authorUserId;
        this.caption = caption;
        this.media = media;
        this.scheduledAt = scheduledAt;
        if (timezone != null && !timezone.isBlank()) {
            this.timezone = timezone;
        }
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getAuthorUserId() {
        return authorUserId;
    }

    public String getCaption() {
        return caption;
    }

    public List<Map<String, Object>> getMedia() {
        return media;
    }

    public OffsetDateTime getScheduledAt() {
        return scheduledAt;
    }

    public String getTimezone() {
        return timezone;
    }

    public String getStatus() {
        return status;
    }

    public String getAyrsharePostId() {
        return ayrsharePostId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setCaption(String caption) {
        if (caption != null && !caption.isBlank()) {
            this.caption = caption;
        }
    }

    public void setMedia(List<Map<String, Object>> media) {
        this.media = media;
    }

    public void setScheduledAt(OffsetDateTime scheduledAt) {
        if (scheduledAt != null) {
            this.scheduledAt = scheduledAt;
        }
    }

    public void setStatus(String status) {
        if (status != null && !status.isBlank()) {
            this.status = status;
        }
    }

    public void setAyrsharePostId(String ayrsharePostId) {
        this.ayrsharePostId = ayrsharePostId;
    }
}
