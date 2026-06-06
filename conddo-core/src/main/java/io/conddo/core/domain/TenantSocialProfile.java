package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * One row per tenant — the Ayrshare User Profile that bundles all the
 * tenant's connected social channels (SOCIAL_AND_CREATIVE_SERVICES_SPEC §2).
 * {@code ayrshareProfileKey} is stored as ciphertext (AES-GCM via
 * {@code SocialTokenCipher}); callers decrypt on read and re-encrypt on
 * write.
 *
 * <p>{@code connectedPlatforms} mirrors what Ayrshare's {@code /api/user}
 * endpoint reports; refreshed lazily (the spec calls out >10-minute staleness
 * as the refresh trigger).
 */
@Entity
@Table(name = "tenant_social_profile")
public class TenantSocialProfile {

    @Id
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "ayrshare_profile_key", nullable = false)
    private String ayrshareProfileKey;

    @Column(name = "ayrshare_profile_title")
    private String ayrshareProfileTitle;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "connected_platforms", nullable = false)
    private List<String> connectedPlatforms = new ArrayList<>();

    @Column(name = "last_synced_at")
    private OffsetDateTime lastSyncedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected TenantSocialProfile() {
    }

    public TenantSocialProfile(UUID tenantId, String encryptedProfileKey, String profileTitle) {
        this.tenantId = tenantId;
        this.ayrshareProfileKey = encryptedProfileKey;
        this.ayrshareProfileTitle = profileTitle;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getAyrshareProfileKey() {
        return ayrshareProfileKey;
    }

    public String getAyrshareProfileTitle() {
        return ayrshareProfileTitle;
    }

    public List<String> getConnectedPlatforms() {
        return connectedPlatforms;
    }

    public OffsetDateTime getLastSyncedAt() {
        return lastSyncedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** Replace the connected-platforms snapshot from a fresh Ayrshare /api/user call. */
    public void refreshConnectedPlatforms(List<String> platforms, OffsetDateTime syncedAt) {
        this.connectedPlatforms = platforms == null
                ? new ArrayList<>()
                : new ArrayList<>(new LinkedHashSet<>(platforms));   // dedup + preserve order
        this.lastSyncedAt = syncedAt;
    }

    /** Drop a single provider from the snapshot — used after a successful Ayrshare unlink. */
    public void removePlatform(String provider) {
        if (provider == null) {
            return;
        }
        this.connectedPlatforms.removeIf(p -> p.equalsIgnoreCase(provider));
    }
}
