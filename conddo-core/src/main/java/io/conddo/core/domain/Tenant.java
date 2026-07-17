package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A business on Conddo.io. The tenant id is the isolation key for every
 * other table. Not itself tenant-scoped (managed by signup / super-admin).
 */
@Entity
@Table(name = "tenants")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
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

    // ----- website (§11.2) — publish state; the site is built in Studio (§8) ---

    @Column(name = "website_status", nullable = false)
    private String websiteStatus = "NOT_STARTED";   // NOT_STARTED | IN_PROGRESS | LIVE

    @Column(name = "website_published_at")
    private OffsetDateTime websitePublishedAt;

    /** Free-text vibe prompt captured at onboarding step 5 — feeds the
     *  WebsiteGenerationService's initial pass + future AI copy passes.
     *  Nullable; a blank vibe means "no strong preference, use vertical
     *  defaults". */
    @Column(name = "website_vibe")
    private String websiteVibe;

    /** Setup-checklist steps (§11.1) the owner has explicitly dismissed. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "setup_dismissed")
    private List<String> setupDismissed = new ArrayList<>();

    // ----- booking config (§11.5) — tenant-level, publicly resolvable ---------

    /** Working hours by weekday, e.g. {@code {"mon":{"open":true,"start":"08:00","end":"18:00"}, ...}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "working_hours")
    private Map<String, Object> workingHours;

    @Column(name = "slot_duration_minutes", nullable = false)
    private int slotDurationMinutes = 60;

    @Column(name = "buffer_minutes", nullable = false)
    private int bufferMinutes = 0;

    @Column(name = "booking_link_slug")
    private String bookingLinkSlug;

    @Column(name = "booking_link_enabled", nullable = false)
    private boolean bookingLinkEnabled = true;

    // ----- settings (§11.11) — tenant profile/branding/social/location/hours ---

    private String tagline;
    private String description;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "contact_phone")
    private String contactPhone;

    @Column(name = "primary_color")
    private String primaryColor;

    @Column(name = "secondary_color")
    private String secondaryColor;

    @Column(name = "font_pairing")
    private String fontPairing;

    @Column(name = "logo_url")
    private String logoUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "social_handles")
    private Map<String, Object> socialHandles;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "location")
    private Map<String, Object> location;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "business_hours")
    private Map<String, Object> businessHours;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notification_prefs")
    private Map<String, Object> notificationPrefs;

    /**
     * Per-tenant email branding (V52). When null, outbound emails fall back to
     * sensible defaults — {@code emailFromName} → {@link #name} so the recipient
     * still sees the tenant, and {@code emailReplyTo} → {@link #contactEmail}
     * so replies go to the tenant's contact.
     */
    @Column(name = "email_from_name", length = 150)
    private String emailFromName;

    @Column(name = "email_reply_to", length = 254)
    private String emailReplyTo;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    /** Soft-delete marker (V71). Null = live tenant. Non-null = admin
     *  marked the tenant for deletion; the row and all children are kept
     *  for audit + recovery but the tenant is hidden from admin lists
     *  and sign-in is refused. Restore by nulling this out. */
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    protected Tenant() {
    }

    public Tenant(String name, String slug, String verticalId, String planId) {
        this.name = name;
        this.slug = slug;
        this.verticalId = verticalId;
        this.planId = planId;
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

    /** Change the tenant's product tier. Used by the auto-downgrade path
     *  (grace → expired → free), the upgrade/downgrade admin path, and any
     *  future customer-driven plan switch. The active {@code TenantSubscription}
     *  row is still the transactional source of truth for billing; this field
     *  is a denormalised cache the JWT-mint reads from. */
    public void changePlanTo(String planId) {
        if (planId == null || planId.isBlank()) {
            throw new IllegalArgumentException("planId cannot be blank");
        }
        this.planId = planId.trim().toLowerCase();
    }

    public String getCustomDomain() {
        return customDomain;
    }

    /** Connects a custom domain (§11.2). Trims/normalises; null clears it. */
    public void setCustomDomain(String customDomain) {
        this.customDomain = (customDomain == null || customDomain.isBlank())
                ? null : customDomain.trim().toLowerCase();
    }

    public String getStatus() {
        return status;
    }

    public String getWebsiteStatus() {
        return websiteStatus;
    }

    public void setWebsiteStatus(String websiteStatus) {
        if (websiteStatus != null && !websiteStatus.isBlank()) {
            this.websiteStatus = websiteStatus;
        }
    }

    public OffsetDateTime getWebsitePublishedAt() {
        return websitePublishedAt;
    }

    public void setWebsitePublishedAt(OffsetDateTime websitePublishedAt) {
        this.websitePublishedAt = websitePublishedAt;
    }

    public String getWebsiteVibe() {
        return websiteVibe;
    }

    public void setWebsiteVibe(String websiteVibe) {
        this.websiteVibe = websiteVibe;
    }

    public List<String> getSetupDismissed() {
        return setupDismissed;
    }

    /** Marks a setup-checklist step (§11.1) as dismissed; idempotent. */
    public void dismissSetupStep(String key) {
        if (setupDismissed == null) {
            setupDismissed = new ArrayList<>();
        }
        if (key != null && !key.isBlank() && !setupDismissed.contains(key)) {
            setupDismissed.add(key);
        }
    }

    public Map<String, Object> getWorkingHours() {
        return workingHours;
    }

    public void setWorkingHours(Map<String, Object> workingHours) {
        this.workingHours = workingHours;
    }

    public int getSlotDurationMinutes() {
        return slotDurationMinutes;
    }

    public void setSlotDurationMinutes(int slotDurationMinutes) {
        if (slotDurationMinutes > 0) {
            this.slotDurationMinutes = slotDurationMinutes;
        }
    }

    public int getBufferMinutes() {
        return bufferMinutes;
    }

    public void setBufferMinutes(int bufferMinutes) {
        if (bufferMinutes >= 0) {
            this.bufferMinutes = bufferMinutes;
        }
    }

    public String getBookingLinkSlug() {
        return bookingLinkSlug;
    }

    public void setBookingLinkSlug(String bookingLinkSlug) {
        this.bookingLinkSlug = bookingLinkSlug;
    }

    public boolean isBookingLinkEnabled() {
        return bookingLinkEnabled;
    }

    public void setBookingLinkEnabled(boolean bookingLinkEnabled) {
        this.bookingLinkEnabled = bookingLinkEnabled;
    }

    /** The effective self-book slug — the configured one, or the tenant slug. */
    public String effectiveBookingSlug() {
        return bookingLinkSlug != null ? bookingLinkSlug : slug;
    }

    // ----- settings (§11.11) --------------------------------------------------

    /** Business name is editable from settings; industry (vertical) and slug are not. */
    public void rename(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    /** Mark the tenant as soft-deleted (V71). Also flips status to
     *  INACTIVE so any code path that predates the deleted_at check
     *  still refuses access to the tenant's data. */
    public void softDelete(OffsetDateTime at) {
        this.deletedAt = at;
        this.status = "INACTIVE";
    }

    /** Restore a soft-deleted tenant. Sets status back to ACTIVE so
     *  the tenant is fully usable again. Idempotent when already live. */
    public void restore() {
        this.deletedAt = null;
        this.status = "ACTIVE";
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public void deactivate() {
        this.status = "INACTIVE";
    }

    public String getTagline() {
        return tagline;
    }

    public void setTagline(String tagline) {
        this.tagline = tagline;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public String getPrimaryColor() {
        return primaryColor;
    }

    public void setPrimaryColor(String primaryColor) {
        this.primaryColor = primaryColor;
    }

    public String getSecondaryColor() {
        return secondaryColor;
    }

    public void setSecondaryColor(String secondaryColor) {
        this.secondaryColor = secondaryColor;
    }

    public String getFontPairing() {
        return fontPairing;
    }

    public void setFontPairing(String fontPairing) {
        this.fontPairing = fontPairing;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public void setLogoUrl(String logoUrl) {
        this.logoUrl = logoUrl;
    }

    public Map<String, Object> getSocialHandles() {
        return socialHandles;
    }

    public void setSocialHandles(Map<String, Object> socialHandles) {
        this.socialHandles = socialHandles;
    }

    public Map<String, Object> getLocation() {
        return location;
    }

    public void setLocation(Map<String, Object> location) {
        this.location = location;
    }

    public Map<String, Object> getBusinessHours() {
        return businessHours;
    }

    public void setBusinessHours(Map<String, Object> businessHours) {
        this.businessHours = businessHours;
    }

    public Map<String, Object> getNotificationPrefs() {
        return notificationPrefs;
    }

    public void setNotificationPrefs(Map<String, Object> notificationPrefs) {
        this.notificationPrefs = notificationPrefs;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    // ----- email branding (V52) -----------------------------------------------

    public String getEmailFromName() {
        return emailFromName;
    }

    /** Null clears the override → outbound emails fall back to the business name. */
    public void setEmailFromName(String emailFromName) {
        this.emailFromName = (emailFromName == null || emailFromName.isBlank())
                ? null : emailFromName.trim();
    }

    public String getEmailReplyTo() {
        return emailReplyTo;
    }

    /** Null clears the override → outbound emails fall back to contactEmail. */
    public void setEmailReplyTo(String emailReplyTo) {
        this.emailReplyTo = (emailReplyTo == null || emailReplyTo.isBlank())
                ? null : emailReplyTo.trim();
    }

    /** The display name to show on outbound emails — override > business name. */
    public String effectiveEmailFromName() {
        return emailFromName != null && !emailFromName.isBlank() ? emailFromName : name;
    }

    /** The reply-to to set on outbound emails — override > contact email > null. */
    public String effectiveEmailReplyTo() {
        if (emailReplyTo != null && !emailReplyTo.isBlank()) {
            return emailReplyTo;
        }
        return contactEmail != null && !contactEmail.isBlank() ? contactEmail : null;
    }
}
