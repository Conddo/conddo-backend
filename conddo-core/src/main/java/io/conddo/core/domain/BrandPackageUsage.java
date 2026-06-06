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
 * One row per (subscription, period) — increment {@code counts} in-place as
 * creative requests consume the quota. Renewal rolls to a fresh row.
 */
@Entity
@Table(name = "brand_package_usage")
public class BrandPackageUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "period_start", nullable = false)
    private OffsetDateTime periodStart;

    @Column(name = "period_end", nullable = false)
    private OffsetDateTime periodEnd;

    /** {@code {offeringCode: usedCount}} — matches the offering's {@code includes} keys. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Integer> counts = new HashMap<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected BrandPackageUsage() {
    }

    public BrandPackageUsage(UUID tenantId, UUID subscriptionId,
                             OffsetDateTime periodStart, OffsetDateTime periodEnd) {
        this.tenantId = tenantId;
        this.subscriptionId = subscriptionId;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    public OffsetDateTime getPeriodStart() {
        return periodStart;
    }

    public OffsetDateTime getPeriodEnd() {
        return periodEnd;
    }

    public Map<String, Integer> getCounts() {
        return counts;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** Increment the count for one offering code. Caller has already checked quota. */
    public void increment(String offeringCode) {
        if (counts == null) {
            counts = new HashMap<>();
        }
        counts.merge(offeringCode, 1, Integer::sum);
    }

    public int countFor(String offeringCode) {
        if (counts == null || offeringCode == null) {
            return 0;
        }
        Integer n = counts.get(offeringCode);
        return n == null ? 0 : n;
    }
}
