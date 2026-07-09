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
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One agent's share of a deal's commission. Accrues at deposit_paid,
 * pays out when the tenant records the settlement.
 */
@Entity
@Table(name = "commission_entries")
public class CommissionEntry {

    public static final String ROLE_PRIMARY = "primary";
    public static final String ROLE_INTRODUCER = "introducer";

    public static final String STATUS_ACCRUED = "accrued";
    public static final String STATUS_PAID_OUT = "paid_out";
    public static final String STATUS_REVERSED = "reversed";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "deal_id", nullable = false, updatable = false)
    private UUID dealId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(nullable = false)
    private String role = ROLE_PRIMARY;

    @Column(name = "split_pct", nullable = false)
    private BigDecimal splitPct = BigDecimal.valueOf(100);

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String status = STATUS_ACCRUED;

    @Column(name = "accrued_at", nullable = false)
    private OffsetDateTime accruedAt = OffsetDateTime.now();

    @Column(name = "paid_out_at")
    private OffsetDateTime paidOutAt;

    @Column(name = "reversed_at")
    private OffsetDateTime reversedAt;

    @Column(name = "reversal_reason")
    private String reversalReason;

    @Column(name = "payment_reference")
    private String paymentReference;

    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected CommissionEntry() {
    }

    public CommissionEntry(UUID tenantId, UUID dealId, UUID agentId,
                           String role, BigDecimal splitPct, BigDecimal amount) {
        this.tenantId = tenantId;
        this.dealId = dealId;
        this.agentId = agentId;
        this.role = role;
        this.splitPct = splitPct;
        this.amount = amount;
    }

    public void markPaidOut(String reference, OffsetDateTime at) {
        this.status = STATUS_PAID_OUT;
        this.paymentReference = reference;
        this.paidOutAt = at;
    }

    public void reverse(String reason, OffsetDateTime at) {
        this.status = STATUS_REVERSED;
        this.reversalReason = reason;
        this.reversedAt = at;
    }

    // Accessors
    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getDealId() { return dealId; }
    public UUID getAgentId() { return agentId; }
    public String getRole() { return role; }
    public BigDecimal getSplitPct() { return splitPct; }
    public BigDecimal getAmount() { return amount; }
    public String getStatus() { return status; }
    public OffsetDateTime getAccruedAt() { return accruedAt; }
    public OffsetDateTime getPaidOutAt() { return paidOutAt; }
    public OffsetDateTime getReversedAt() { return reversedAt; }
    public String getReversalReason() { return reversalReason; }
    public String getPaymentReference() { return paymentReference; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
