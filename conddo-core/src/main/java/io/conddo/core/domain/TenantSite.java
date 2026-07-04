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
import java.util.HashMap;
import java.util.Map;
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

    // ----- managed site (V60, Path A: Conddo hosts + AI-generates) ----------
    //
    // Two-slot content model: draft is what the owner edits/previews;
    // sections+theme is what the public renderer serves. Promote from draft
    // to live by copying refs + stamping published_at.

    /** Live published site sections. Rendered to public visitors at
     *  {@code <slug>.getconddo.com}. Null before first publish. */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> sections;

    /** Live theme tokens (colors, fonts, spacing). Null before first publish. */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> theme;

    /** Working copy of {@link #sections}. Non-null once AI has generated the
     *  initial site; the owner can edit here without affecting the live site. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft_sections")
    private Map<String, Object> draftSections;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft_theme")
    private Map<String, Object> draftTheme;

    /** When {@link #sections}/{@link #theme} were last set by a publish
     *  action. Null means the site is drafted but not live. */
    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    /** {@code true} when this row represents a Conddo-hosted managed site
     *  (Path A). Legacy external-submission rows stay {@code false} — their
     *  behavior is unchanged. */
    @Column(nullable = false)
    private boolean managed = false;

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

    /** Managed-site constructor (Path A) — no api key, generated content instead. */
    public static TenantSite managed(UUID tenantId, String subdomain,
                                     Map<String, Object> draftSections,
                                     Map<String, Object> draftTheme) {
        TenantSite site = new TenantSite();
        site.tenantId = tenantId;
        site.subdomain = subdomain;
        site.managed = true;
        site.draftSections = draftSections;
        site.draftTheme = draftTheme;
        // No API key — the constructor requires non-null hash/last4 for legacy
        // rows, so we set placeholders that make it obvious this is a managed
        // site if something ever reads them.
        site.apiKeyHash = "";
        site.apiKeyLast4 = "MGD";
        return site;
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

    // ----- managed-site mutators -------------------------------------------

    /** Replace the working copy — used by the FE editor's Save and by the AI
     *  generator to seed the initial draft. Does not touch the live copy. */
    public void updateDraft(Map<String, Object> sections, Map<String, Object> theme) {
        if (sections != null) this.draftSections = sections;
        if (theme != null) this.draftTheme = theme;
    }

    /** Promote draft to live. Stamps {@link #publishedAt} so the public
     *  renderer's partial index picks the row up. Also flips {@link #managed}
     *  in case a legacy row is being converted (idempotent otherwise). */
    public void publishDraft(OffsetDateTime at) {
        // Copy references — the draft is authoritative until superseded.
        this.sections = this.draftSections != null ? new HashMap<>(this.draftSections) : null;
        this.theme = this.draftTheme != null ? new HashMap<>(this.draftTheme) : null;
        this.publishedAt = at;
        this.managed = true;
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

    public Map<String, Object> getSections() {
        return sections;
    }

    public Map<String, Object> getTheme() {
        return theme;
    }

    public Map<String, Object> getDraftSections() {
        return draftSections;
    }

    public Map<String, Object> getDraftTheme() {
        return draftTheme;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public boolean isManaged() {
        return managed;
    }

    /** The masked display value the FE shows in the non-regenerate state. */
    public String maskedKey() {
        return "sk_live_••••••••" + apiKeyLast4;
    }
}
