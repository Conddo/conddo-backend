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
 * One open reconciliation session (Pharmacy Spec v2 §12A). The session
 * captures system stock counts as a baseline, the pharmacist fills in
 * physical counts product-by-product, and {@code complete()} applies
 * variances as {@code RECONCILIATION}-typed stock movements.
 */
@Entity
@Table(name = "pharmacy_reconciliations")
public class PharmacyReconciliation {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 20)
    private String status = IN_PROGRESS;

    @Column(name = "started_by", nullable = false)
    private UUID startedBy;

    @Column(name = "completed_by")
    private UUID completedBy;

    @CreationTimestamp
    @Column(name = "started_at", updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    private String notes;

    protected PharmacyReconciliation() {
    }

    public PharmacyReconciliation(UUID tenantId, UUID startedBy, String notes) {
        this.tenantId = tenantId;
        this.startedBy = startedBy;
        this.notes = notes;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getStatus() {
        return status;
    }

    public UUID getStartedBy() {
        return startedBy;
    }

    public UUID getCompletedBy() {
        return completedBy;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public String getNotes() {
        return notes;
    }

    public void markCompleted(UUID by, OffsetDateTime at) {
        this.status = COMPLETED;
        this.completedBy = by;
        this.completedAt = at;
    }

    public void markCancelled(UUID by, OffsetDateTime at) {
        this.status = CANCELLED;
        this.completedBy = by;
        this.completedAt = at;
    }
}
