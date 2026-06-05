package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The tenant's public website integration record (WEBSITE_INTEGRATION_SPEC §1).
 * Stores a bcrypt of the API key — never the plaintext. Plaintext leaves the
 * service only at the moment of generation or rotation; subsequent reads
 * return {@code apiKeyMasked} only.
 *
 * <p>Tenant-scoped via the RLS policy added in V25. Public traffic resolves
 * by {@code subdomain}; the bcrypt compare for the header API key happens
 * service-side after lookup.
 */
@Entity
@Table(name = "tenant_sites")
public class TenantSite {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(unique = true)
    private String subdomain;

    @Column(name = "custom_domain", unique = true)
    private String customDomain;

    @Column(name = "hosting_provider")
    private String hostingProvider;

    @Column(name = "site_type")
    private String siteType;

    /** bcrypt hash of the plaintext key. */
    @Column(name = "api_key_hash", nullable = false)
    private String apiKeyHash;

    /** Last 4 plaintext chars for the masked display {@code sk_live_••••••••a3f2}. */
    @Column(name = "api_key_last4", nullable = false)
    private String apiKeyLast4;

    @Column(name = "is_active", nullable = false)
    private boolean active = false;

    @Column(name = "qa_approved", nullable = false)
    private boolean qaApproved = false;

    @Column(name = "qa_approved_by")
    private UUID qaApprovedBy;

    @Column(name = "qa_approved_at")
    private OffsetDateTime qaApprovedAt;

    @Column(name = "submitted_url")
    private String submittedUrl;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected TenantSite() {
    }

    public TenantSite(UUID tenantId, String subdomain, String apiKeyHash, String apiKeyLast4) {
        this.tenantId = tenantId;
        this.subdomain = subdomain;
        this.apiKeyHash = apiKeyHash;
        this.apiKeyLast4 = apiKeyLast4;
    }

    /** Rotate to a new key — caller computes the bcrypt + last4. */
    public void rotateKey(String newHash, String newLast4) {
        this.apiKeyHash = newHash;
        this.apiKeyLast4 = newLast4;
    }

    public void approveQa(UUID staffId, OffsetDateTime at) {
        this.qaApproved = true;
        this.qaApprovedBy = staffId;
        this.qaApprovedAt = at;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    public void setSubdomain(String subdomain) {
        if (subdomain != null && !subdomain.isBlank()) {
            this.subdomain = subdomain;
        }
    }

    public void setCustomDomain(String customDomain) {
        this.customDomain = customDomain;
    }

    public void setHostingProvider(String hostingProvider) {
        this.hostingProvider = hostingProvider;
    }

    public void setSiteType(String siteType) {
        this.siteType = siteType;
    }

    public void setSubmittedUrl(String submittedUrl) {
        this.submittedUrl = submittedUrl;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getSubdomain() {
        return subdomain;
    }

    public String getCustomDomain() {
        return customDomain;
    }

    public String getHostingProvider() {
        return hostingProvider;
    }

    public String getSiteType() {
        return siteType;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public String getApiKeyLast4() {
        return apiKeyLast4;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isQaApproved() {
        return qaApproved;
    }

    public UUID getQaApprovedBy() {
        return qaApprovedBy;
    }

    public OffsetDateTime getQaApprovedAt() {
        return qaApprovedAt;
    }

    public String getSubmittedUrl() {
        return submittedUrl;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** The masked display value the FE shows in the non-regenerate state. */
    public String maskedKey() {
        return "sk_live_••••••••" + apiKeyLast4;
    }
}
