package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Clinical follow-up Conddo schedules for a patient after dispensing
 * medication (Pharmacy Roadmap Beta 2). Lifecycle:
 * PENDING → COMPLETED (outcome recorded) or CANCELLED (manual);
 * the daily missed-sweep cron flips PENDING rows to MISSED 48h after
 * due_date if no outcome was recorded.
 */
@Entity
@Table(name = "pharmacy_followups")
public class PharmacyFollowup {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_MISSED = "MISSED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "due_date", nullable = false)
    private OffsetDateTime dueDate;

    @Column(name = "check_note", nullable = false, columnDefinition = "TEXT")
    private String checkNote;

    @Column(nullable = false, length = 20)
    private String status = STATUS_PENDING;

    private String outcome;

    @Column(name = "outcome_type", length = 30)
    private String outcomeType;

    @Column(name = "completed_by")
    private UUID completedBy;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected PharmacyFollowup() {
    }

    public PharmacyFollowup(UUID tenantId, UUID customerId, UUID orderId, UUID productId,
                             OffsetDateTime dueDate, String checkNote, UUID createdBy) {
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.orderId = orderId;
        this.productId = productId;
        this.dueDate = dueDate;
        this.checkNote = checkNote;
        this.createdBy = createdBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getProductId() {
        return productId;
    }

    public OffsetDateTime getDueDate() {
        return dueDate;
    }

    public String getCheckNote() {
        return checkNote;
    }

    public String getStatus() {
        return status;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getOutcomeType() {
        return outcomeType;
    }

    public UUID getCompletedBy() {
        return completedBy;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void complete(String outcome, String outcomeType, UUID by, OffsetDateTime at) {
        this.status = STATUS_COMPLETED;
        this.outcome = outcome;
        this.outcomeType = outcomeType;
        this.completedBy = by;
        this.completedAt = at;
    }

    public void cancel() {
        this.status = STATUS_CANCELLED;
    }

    public void markMissed() {
        this.status = STATUS_MISSED;
    }
}
