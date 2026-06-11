package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One cashback wallet per (tenant, customer) pair (Pharmacy Roadmap
 * Beta 1). Balance updates are append-only via the transactions
 * ledger so a balance row in isolation always reflects the sum of
 * its transactions.
 */
@Entity
@Table(name = "pharmacy_customer_wallets")
public class PharmacyCustomerWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "total_earned", nullable = false)
    private BigDecimal totalEarned = BigDecimal.ZERO;

    @Column(name = "total_redeemed", nullable = false)
    private BigDecimal totalRedeemed = BigDecimal.ZERO;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected PharmacyCustomerWallet() {
    }

    public PharmacyCustomerWallet(UUID tenantId, UUID customerId) {
        this.tenantId = tenantId;
        this.customerId = customerId;
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

    public BigDecimal getBalance() {
        return balance;
    }

    public BigDecimal getTotalEarned() {
        return totalEarned;
    }

    public BigDecimal getTotalRedeemed() {
        return totalRedeemed;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** Apply a credit (positive) — bumps balance + total_earned. */
    public void credit(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("credit amount must be > 0");
        }
        this.balance = this.balance.add(amount);
        this.totalEarned = this.totalEarned.add(amount);
    }

    /** Apply a debit (positive amount) — guards against negative balance. */
    public void debit(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("debit amount must be > 0");
        }
        if (this.balance.compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient wallet balance");
        }
        this.balance = this.balance.subtract(amount);
        this.totalRedeemed = this.totalRedeemed.add(amount);
    }
}
