package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Settlement record — one row per provider payout to a tenant's bank
 * account. Populated by the provider's payout webhook. Bank details are
 * denormalised so a later bank change doesn't rewrite history.
 */
@Entity
@Table(name = "payouts")
public class Payout {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";

    @Id private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Column(nullable = false) private String provider;
    @Column(name = "provider_reference", nullable = false) private String providerReference;

    @Column(name = "amount_kobo", nullable = false) private long amountKobo;
    @Column(nullable = false) private String currency = "NGN";

    @Column(name = "bank_name") private String bankName;
    @Column(name = "account_number_last4") private String accountNumberLast4;
    @Column(name = "account_name") private String accountName;

    @Column(nullable = false) private String status = STATUS_PENDING;
    @Column(name = "failure_reason") private String failureReason;

    @Column(name = "initiated_at", nullable = false) private OffsetDateTime initiatedAt;
    @Column(name = "completed_at") private OffsetDateTime completedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (initiatedAt == null) initiatedAt = OffsetDateTime.now();
    }

    public void markSucceeded() {
        this.status = STATUS_SUCCEEDED;
        this.completedAt = OffsetDateTime.now();
    }

    public void markFailed(String reason) {
        this.status = STATUS_FAILED;
        this.failureReason = reason;
        this.completedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID v) { this.tenantId = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getProviderReference() { return providerReference; }
    public void setProviderReference(String v) { this.providerReference = v; }
    public long getAmountKobo() { return amountKobo; }
    public void setAmountKobo(long v) { this.amountKobo = v; }
    public String getCurrency() { return currency; }
    public String getBankName() { return bankName; }
    public void setBankName(String v) { this.bankName = v; }
    public String getAccountNumberLast4() { return accountNumberLast4; }
    public void setAccountNumberLast4(String v) { this.accountNumberLast4 = v; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String v) { this.accountName = v; }
    public String getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public OffsetDateTime getInitiatedAt() { return initiatedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
}
