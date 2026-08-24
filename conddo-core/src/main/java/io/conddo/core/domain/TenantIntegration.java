package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A tenant's connected payment account (Moniepoint / OPay). One row per
 * (tenant, provider). Credentials live encrypted at rest in the
 * {@code credentials} JSONB column (each field AES-GCM-wrapped by
 * {@code SecretCipher}); the provider-returned terminal / business
 * snapshot is denormalised onto the row so the connected-accounts screen
 * renders without a provider round-trip.
 *
 * <p>Status: {@code connected} (verified + live), {@code error} (last
 * verification or sync failed — see {@code lastError}), or
 * {@code disconnected} (tenant removed the account).
 */
@Entity
@Table(name = "tenant_integrations")
public class TenantIntegration {

    public static final String PROVIDER_MONIEPOINT = "moniepoint";
    public static final String PROVIDER_OPAY = "opay";
    /** Tenant's own Paystack secret. Separate from the platform's
     *  Paystack key which handles Conddo subscription billing. */
    public static final String PROVIDER_PAYSTACK = "paystack";

    public static final String STATUS_CONNECTED = "connected";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_DISCONNECTED = "disconnected";

    @Id
    @Column(name = "id", updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String status = STATUS_CONNECTED;

    @Column
    private String label;

    @Column(name = "business_name")
    private String businessName;

    @Column(name = "account_name")
    private String accountName;

    @Column(name = "account_number")
    private String accountNumber;

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "terminal_serial")
    private String terminalSerial;

    /** Encrypted credential fields, JSON object: {@code {"apiKey":"<enc>"}} etc. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String credentials = "{}";

    /** Non-secret lookup key for webhook tenant-resolution: OPay
     *  merchantId as-is, or SHA-256 of the Moniepoint api key. */
    @Column(name = "merchant_reference")
    private String merchantReference;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "last_checked_at")
    private OffsetDateTime lastCheckedAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected TenantIntegration() {
    }

    public TenantIntegration(UUID tenantId, String provider) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.provider = provider;
    }

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() { updatedAt = OffsetDateTime.now(); }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getProvider() { return provider; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getLabel() { return label; }
    public void setLabel(String v) { this.label = v; }
    public String getBusinessName() { return businessName; }
    public void setBusinessName(String v) { this.businessName = v; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String v) { this.accountName = v; }
    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String v) { this.accountNumber = v; }
    public String getBankName() { return bankName; }
    public void setBankName(String v) { this.bankName = v; }
    public String getTerminalSerial() { return terminalSerial; }
    public void setTerminalSerial(String v) { this.terminalSerial = v; }
    public String getCredentials() { return credentials; }
    public void setCredentials(String v) { this.credentials = v == null ? "{}" : v; }
    public String getMerchantReference() { return merchantReference; }
    public void setMerchantReference(String v) { this.merchantReference = v; }
    public OffsetDateTime getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(OffsetDateTime v) { this.verifiedAt = v; }
    public OffsetDateTime getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(OffsetDateTime v) { this.lastCheckedAt = v; }
    public String getLastError() { return lastError; }
    public void setLastError(String v) { this.lastError = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    /** Mark the connection as verified-live and snapshot the provider info. */
    public void markVerified(String label, String businessName, String accountName,
                             String accountNumber, String bankName, String terminalSerial,
                             String merchantReference) {
        this.status = STATUS_CONNECTED;
        this.label = label;
        this.businessName = businessName;
        this.accountName = accountName;
        this.accountNumber = accountNumber;
        this.bankName = bankName;
        this.terminalSerial = terminalSerial;
        this.merchantReference = merchantReference;
        this.verifiedAt = OffsetDateTime.now();
        this.lastCheckedAt = this.verifiedAt;
        this.lastError = null;
    }

    /** Mark a failed verification / sync. The stored credentials stay. */
    public void markError(String message) {
        this.status = STATUS_ERROR;
        this.lastCheckedAt = OffsetDateTime.now();
        this.lastError = message;
    }

    public void markDisconnected() {
        this.status = STATUS_DISCONNECTED;
        this.lastCheckedAt = OffsetDateTime.now();
    }
}
