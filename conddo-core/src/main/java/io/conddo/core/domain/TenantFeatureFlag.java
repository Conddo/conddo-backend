package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Per-tenant feature flag (Pharmacy Roadmap). A row exists once the
 * tenant has interacted — clicked "Notify me when ready" (interest =
 * true) or "Request Beta Access" (interest = true, awaiting grant) —
 * or when the Conddo team granted access (enabled = true). The {@code
 * status} column is denormalised from
 * {@link io.conddo.core.features.FeatureCatalogue} for historical
 * accuracy.
 */
@Entity
@Table(name = "tenant_feature_flags")
public class TenantFeatureFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "feature_key", nullable = false, length = 100)
    private String featureKey;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private boolean interest;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "interest_at")
    private OffsetDateTime interestAt;

    @Column(name = "granted_at")
    private OffsetDateTime grantedAt;

    @Column(name = "granted_by")
    private UUID grantedBy;

    protected TenantFeatureFlag() {
    }

    public TenantFeatureFlag(UUID tenantId, String featureKey, String status) {
        this.tenantId = tenantId;
        this.featureKey = featureKey;
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getFeatureKey() {
        return featureKey;
    }

    public String getStatus() {
        return status;
    }

    public boolean isInterest() {
        return interest;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public OffsetDateTime getInterestAt() {
        return interestAt;
    }

    public OffsetDateTime getGrantedAt() {
        return grantedAt;
    }

    public UUID getGrantedBy() {
        return grantedBy;
    }

    public void recordInterest(OffsetDateTime at) {
        if (!this.interest) {
            this.interest = true;
            this.interestAt = at;
        }
    }

    public void grant(UUID by, OffsetDateTime at) {
        this.enabled = true;
        this.grantedBy = by;
        this.grantedAt = at;
    }

    public void revoke() {
        this.enabled = false;
        this.grantedBy = null;
        this.grantedAt = null;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
