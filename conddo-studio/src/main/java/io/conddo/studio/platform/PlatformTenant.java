package io.conddo.studio.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Studio's read-only mirror of {@code public.tenants} (Infrastructure §23.2).
 * Maps only the columns Studio admins actually need; the full {@code Tenant}
 * entity lives in {@code conddo-core} and is the source of truth.
 *
 * <p>Lives in {@code public} schema explicitly so Hibernate's
 * {@code default_schema=studio} setting doesn't mis-qualify it. The Studio
 * service connects as {@code conddo_owner}, the row-policy owner role — it
 * sees every tenant across the platform without RLS scoping (and without
 * BYPASSRLS, which is intentionally never granted).
 */
@Entity
@Table(name = "tenants", schema = "public")
public class PlatformTenant {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "vertical_id")
    private String verticalId;

    @Column(name = "plan_id")
    private String planId;

    @Column(name = "custom_domain")
    private String customDomain;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "website_status", nullable = false)
    private String websiteStatus = "NOT_STARTED";

    @Column(name = "website_published_at")
    private OffsetDateTime websitePublishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected PlatformTenant() {
    }

    /** Phase 13b mutators (deferred) — suspend / reactivate flip {@code status}. */
    public void setStatus(String status) {
        if (status != null && !status.isBlank()) {
            this.status = status;
        }
    }

    public void rename(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    public void setPlanId(String planId) {
        if (planId != null && !planId.isBlank()) {
            this.planId = planId;
        }
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public String getVerticalId() {
        return verticalId;
    }

    public String getPlanId() {
        return planId;
    }

    public String getCustomDomain() {
        return customDomain;
    }

    public String getStatus() {
        return status;
    }

    public String getWebsiteStatus() {
        return websiteStatus;
    }

    public OffsetDateTime getWebsitePublishedAt() {
        return websitePublishedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
