package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Product-scoped discount (Pharmacy Spec v2 §12B). Goes through an
 * admin-approval workflow; once {@code APPROVED}, applies during
 * {@code [starts_at, ends_at]} on product GETs and at order checkout.
 * Snapshotted into {@code OrderItem.snapshot} so the customer-paid
 * price is preserved after the discount expires.
 */
@Entity
@Table(name = "pharmacy_discounts")
public class PharmacyDiscount {

    public static final String TYPE_PERCENTAGE = "PERCENTAGE";
    public static final String TYPE_FIXED = "FIXED";

    public static final String STATUS_PENDING = "PENDING_APPROVAL";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "discount_type", nullable = false, length = 20)
    private String discountType;

    @Column(name = "discount_value", nullable = false)
    private BigDecimal discountValue;

    private String label;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at")
    private OffsetDateTime endsAt;

    @Column(nullable = false, length = 20)
    private String status = STATUS_PENDING;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "rejection_note")
    private String rejectionNote;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected PharmacyDiscount() {
    }

    public PharmacyDiscount(UUID tenantId, UUID productId, String discountType,
                            BigDecimal discountValue, String label,
                            OffsetDateTime startsAt, OffsetDateTime endsAt,
                            UUID createdBy) {
        this.tenantId = tenantId;
        this.productId = productId;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.label = label;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.createdBy = createdBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getDiscountType() {
        return discountType;
    }

    public BigDecimal getDiscountValue() {
        return discountValue;
    }

    public String getLabel() {
        return label;
    }

    public OffsetDateTime getStartsAt() {
        return startsAt;
    }

    public OffsetDateTime getEndsAt() {
        return endsAt;
    }

    public String getStatus() {
        return status;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public OffsetDateTime getApprovedAt() {
        return approvedAt;
    }

    public String getRejectionNote() {
        return rejectionNote;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void approve(UUID by, OffsetDateTime at) {
        this.status = STATUS_APPROVED;
        this.approvedBy = by;
        this.approvedAt = at;
    }

    public void reject(UUID by, OffsetDateTime at, String note) {
        this.status = STATUS_REJECTED;
        this.approvedBy = by;
        this.approvedAt = at;
        this.rejectionNote = note;
    }

    public void markExpired() {
        this.status = STATUS_EXPIRED;
    }

    /**
     * True when the discount is currently usable: {@code APPROVED} and
     * the wall-clock falls in {@code [startsAt, endsAt]}. An open-ended
     * {@code endsAt} (null) means "no expiry".
     */
    public boolean isActiveAt(OffsetDateTime when) {
        if (!STATUS_APPROVED.equals(status)) {
            return false;
        }
        if (when.isBefore(startsAt)) {
            return false;
        }
        return endsAt == null || !when.isAfter(endsAt);
    }

    /**
     * Apply the discount to a list price, never going below zero.
     * Result is rounded to 2 decimal places.
     */
    public BigDecimal applyTo(BigDecimal price) {
        if (price == null) {
            return null;
        }
        BigDecimal discounted = switch (discountType) {
            case TYPE_PERCENTAGE -> price.subtract(price.multiply(discountValue)
                    .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
            case TYPE_FIXED -> price.subtract(discountValue);
            default -> price;
        };
        if (discounted.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return discounted.setScale(2, RoundingMode.HALF_UP);
    }
}
