package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Append-only ledger of every wallet movement (Pharmacy Roadmap
 * Beta 1). Carries a signed amount and an optional reference_id
 * pointing back to the originating row (order_id for
 * CASHBACK_EARNED / REDEMPTION, etc.).
 */
@Entity
@Table(name = "pharmacy_wallet_transactions")
public class PharmacyWalletTransaction {

    public static final String CASHBACK_EARNED = "CASHBACK_EARNED";
    public static final String REDEMPTION = "REDEMPTION";
    public static final String ADJUSTMENT = "ADJUSTMENT";
    public static final String EXPIRY = "EXPIRY";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "transaction_type", nullable = false, length = 20)
    private String transactionType;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "reference_id")
    private UUID referenceId;

    private String note;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected PharmacyWalletTransaction() {
    }

    public PharmacyWalletTransaction(UUID tenantId, UUID walletId, String type, BigDecimal amount,
                                     UUID referenceId, String note) {
        this.tenantId = tenantId;
        this.walletId = walletId;
        this.transactionType = type;
        this.amount = amount;
        this.referenceId = referenceId;
        this.note = note;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public String getNote() {
        return note;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
