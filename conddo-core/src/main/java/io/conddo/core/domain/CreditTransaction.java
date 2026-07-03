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
 * One row per credit event. Sync paths write a single {@code CONSUMED}
 * row; async paths write {@code RESERVED} first, then flip to
 * {@code CONSUMED} or {@code RELEASED} on completion.
 */
@Entity
@Table(name = "credit_transactions")
public class CreditTransaction {

    public static final String STATUS_RESERVED = "RESERVED";
    public static final String STATUS_CONSUMED = "CONSUMED";
    public static final String STATUS_RELEASED = "RELEASED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "action_type", nullable = false, updatable = false)
    private String actionType;

    @Column(name = "credits_consumed", nullable = false, updatable = false)
    private int creditsConsumed;

    @Column(nullable = false)
    private String status = STATUS_CONSUMED;

    @Column(name = "reserved_expires_at")
    private OffsetDateTime reservedExpiresAt;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "reference_type")
    private String referenceType;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    protected CreditTransaction() {
    }

    /** Sync path — records a consumed transaction in one go. */
    public static CreditTransaction consumed(UUID tenantId, String actionType, int credits,
                                             UUID referenceId, String referenceType,
                                             OffsetDateTime now) {
        CreditTransaction tx = new CreditTransaction();
        tx.tenantId = tenantId;
        tx.actionType = actionType;
        tx.creditsConsumed = credits;
        tx.status = STATUS_CONSUMED;
        tx.referenceId = referenceId;
        tx.referenceType = referenceType;
        tx.resolvedAt = now;
        return tx;
    }

    /** Async path — records a reservation that will be confirmed or released. */
    public static CreditTransaction reserved(UUID tenantId, String actionType, int credits,
                                             UUID referenceId, String referenceType,
                                             OffsetDateTime expiresAt) {
        CreditTransaction tx = new CreditTransaction();
        tx.tenantId = tenantId;
        tx.actionType = actionType;
        tx.creditsConsumed = credits;
        tx.status = STATUS_RESERVED;
        tx.reservedExpiresAt = expiresAt;
        tx.referenceId = referenceId;
        tx.referenceType = referenceType;
        return tx;
    }

    public void confirm(OffsetDateTime now) {
        this.status = STATUS_CONSUMED;
        this.reservedExpiresAt = null;
        this.resolvedAt = now;
    }

    public void release(OffsetDateTime now) {
        this.status = STATUS_RELEASED;
        this.reservedExpiresAt = null;
        this.resolvedAt = now;
    }

    // ----- accessors --------------------------------------------------------

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getActionType() { return actionType; }
    public int getCreditsConsumed() { return creditsConsumed; }
    public String getStatus() { return status; }
    public OffsetDateTime getReservedExpiresAt() { return reservedExpiresAt; }
    public UUID getReferenceId() { return referenceId; }
    public String getReferenceType() { return referenceType; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
}
