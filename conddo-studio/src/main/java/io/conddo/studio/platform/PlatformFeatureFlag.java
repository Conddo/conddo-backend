package io.conddo.studio.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Studio's mirror of {@code public.tenant_feature_flags} (V39).
 * Studio runs as {@code conddo_owner}, the table-owning role, so it
 * sees and writes every row across all tenants without RLS scoping.
 */
@Entity
@Table(name = "tenant_feature_flags", schema = "public")
public class PlatformFeatureFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "feature_key", nullable = false, length = 100)
    private String featureKey;

    @Column(nullable = false, length = 20)
    private String status = "coming_soon";

    @Column(nullable = false)
    private boolean interest = false;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(name = "interest_at")
    private OffsetDateTime interestAt;

    @Column(name = "granted_at")
    private OffsetDateTime grantedAt;

    @Column(name = "granted_by")
    private UUID grantedBy;

    protected PlatformFeatureFlag() {
    }

    /**
     * Seed a fresh row for the rare case where ops grants a feature
     * before the tenant has expressed interest (no upstream row to
     * mutate). Status defaults to "beta" — direct ops-grant implies
     * the feature is at least beta-ready.
     */
    public static PlatformFeatureFlag forGrant(UUID tenantId, String featureKey, String status) {
        PlatformFeatureFlag flag = new PlatformFeatureFlag();
        flag.tenantId = tenantId;
        flag.featureKey = featureKey;
        flag.status = status == null ? "beta" : status;
        return flag;
    }

    public void grant(UUID actorId, OffsetDateTime now) {
        this.enabled = true;
        this.grantedAt = now;
        this.grantedBy = actorId;
    }

    public void revoke() {
        this.enabled = false;
        // Keep granted_at / granted_by so the row stays auditable —
        // status is derived as "revoked" when enabled=false AND granted_at IS NOT NULL.
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getFeatureKey() { return featureKey; }
    public String getStatus() { return status; }
    public boolean isInterest() { return interest; }
    public boolean isEnabled() { return enabled; }
    public OffsetDateTime getInterestAt() { return interestAt; }
    public OffsetDateTime getGrantedAt() { return grantedAt; }
    public UUID getGrantedBy() { return grantedBy; }
}
