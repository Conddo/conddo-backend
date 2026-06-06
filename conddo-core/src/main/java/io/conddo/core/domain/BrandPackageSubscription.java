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
 * A tenant's brand-package subscription
 * (SOCIAL_AND_CREATIVE_SERVICES_SPEC §6). Status lifecycle:
 * {@code pending_payment → active → past_due → cancelled} (or active → cancelled).
 */
@Entity
@Table(name = "brand_package_subscriptions")
public class BrandPackageSubscription {

    public static final String STATUS_PENDING_PAYMENT = "pending_payment";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_PAST_DUE = "past_due";
    public static final String STATUS_CANCELLED = "cancelled";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "offering_id", nullable = false)
    private UUID offeringId;

    @Column(nullable = false)
    private String status = STATUS_PENDING_PAYMENT;

    @Column(name = "current_period_start", nullable = false)
    private OffsetDateTime currentPeriodStart;

    @Column(name = "current_period_end", nullable = false)
    private OffsetDateTime currentPeriodEnd;

    @Column(name = "payment_reference")
    private String paymentReference;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected BrandPackageSubscription() {
    }

    public BrandPackageSubscription(UUID tenantId, UUID offeringId,
                                    OffsetDateTime periodStart, OffsetDateTime periodEnd) {
        this.tenantId = tenantId;
        this.offeringId = offeringId;
        this.currentPeriodStart = periodStart;
        this.currentPeriodEnd = periodEnd;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getOfferingId() {
        return offeringId;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getCurrentPeriodStart() {
        return currentPeriodStart;
    }

    public OffsetDateTime getCurrentPeriodEnd() {
        return currentPeriodEnd;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public OffsetDateTime getCancelledAt() {
        return cancelledAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setPaymentReference(String paymentReference) {
        this.paymentReference = paymentReference;
    }

    /** Payment confirmed — flip pending_payment / past_due → active. Idempotent. */
    public void markPaid(String paymentReference) {
        this.paymentReference = paymentReference;
        if (STATUS_PENDING_PAYMENT.equals(this.status) || STATUS_PAST_DUE.equals(this.status)) {
            this.status = STATUS_ACTIVE;
        }
    }

    /** Renewal charge bumped the period. */
    public void roll(OffsetDateTime periodStart, OffsetDateTime periodEnd) {
        this.currentPeriodStart = periodStart;
        this.currentPeriodEnd = periodEnd;
        this.status = STATUS_ACTIVE;
    }

    public void markPastDue() {
        if (STATUS_ACTIVE.equals(this.status)) {
            this.status = STATUS_PAST_DUE;
        }
    }

    public void cancel(OffsetDateTime at) {
        this.status = STATUS_CANCELLED;
        this.cancelledAt = at;
    }
}
