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

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

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

    public String getCustomDomain() {
        return customDomain;
    }

    public String getStatus() {
        return status;
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

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
