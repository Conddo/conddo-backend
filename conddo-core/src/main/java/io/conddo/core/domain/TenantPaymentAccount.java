package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Per-tenant payment settlement account + KYC lifecycle.
 *
 * <p>KYC is a two-sided flow: the tenant uploads CAC / director ID /
 * utility bill under {@code /settings/payments}, which flips
 * {@code kycStatus} to {@code under_review}. A Conddo admin then
 * approves or rejects in {@code /admin/tenants/[id]/kyc}. Only when
 * {@code kycStatus = 'approved'} AND a verified bank account is on file
 * does {@code paymentsEnabled} flip true.
 *
 * <p>Importapay does not require merchant KYC from us — we run this
 * flow purely for our own AML/CFT compliance posture.
 */
@Entity
@Table(name = "tenant_payment_accounts")
public class TenantPaymentAccount {

    public static final String KYC_PENDING = "pending";
    public static final String KYC_UNDER_REVIEW = "under_review";
    public static final String KYC_APPROVED = "approved";
    public static final String KYC_REJECTED = "rejected";

    @Id
    @Column(name = "tenant_id", updatable = false)
    private UUID tenantId;

    @Column(name = "bank_code") private String bankCode;
    @Column(name = "bank_name") private String bankName;
    @Column(name = "account_number") private String accountNumber;
    @Column(name = "account_name") private String accountName;
    @Column(name = "account_verified_at") private OffsetDateTime accountVerifiedAt;

    @Column(name = "kyc_status", nullable = false) private String kycStatus = KYC_PENDING;
    @Column(name = "kyc_submitted_at") private OffsetDateTime kycSubmittedAt;
    @Column(name = "kyc_reviewed_at") private OffsetDateTime kycReviewedAt;
    @Column(name = "kyc_reviewed_by") private UUID kycReviewedBy;
    @Column(name = "kyc_rejection_reason") private String kycRejectionReason;

    @Column(name = "kyc_cac_document_url") private String kycCacDocumentUrl;
    @Column(name = "kyc_director_id_url") private String kycDirectorIdUrl;
    @Column(name = "kyc_utility_bill_url") private String kycUtilityBillUrl;
    @Column(name = "kyc_business_address") private String kycBusinessAddress;

    @Column(name = "importapay_merchant_id") private String importapayMerchantId;
    @Column(name = "routepay_merchant_id") private String routepayMerchantId;
    @Column(name = "paystack_subaccount_code") private String paystackSubaccountCode;

    @Column(name = "payments_enabled", nullable = false) private boolean paymentsEnabled = false;

    @Column(name = "created_at", nullable = false, updatable = false) private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() { updatedAt = OffsetDateTime.now(); }

    /** Tenant just uploaded KYC docs. Move from pending/rejected -> under_review. */
    public void submitForReview() {
        this.kycStatus = KYC_UNDER_REVIEW;
        this.kycSubmittedAt = OffsetDateTime.now();
        this.kycRejectionReason = null;
    }

    /** Admin approves. Flip paymentsEnabled only if bank is also verified. */
    public void approve(UUID reviewerId) {
        this.kycStatus = KYC_APPROVED;
        this.kycReviewedAt = OffsetDateTime.now();
        this.kycReviewedBy = reviewerId;
        this.kycRejectionReason = null;
        this.paymentsEnabled = this.accountVerifiedAt != null;
    }

    /** Admin rejects with a reason the tenant will see. */
    public void reject(UUID reviewerId, String reason) {
        this.kycStatus = KYC_REJECTED;
        this.kycReviewedAt = OffsetDateTime.now();
        this.kycReviewedBy = reviewerId;
        this.kycRejectionReason = reason;
        this.paymentsEnabled = false;
    }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID v) { this.tenantId = v; }
    public String getBankCode() { return bankCode; }
    public void setBankCode(String v) { this.bankCode = v; }
    public String getBankName() { return bankName; }
    public void setBankName(String v) { this.bankName = v; }
    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String v) { this.accountNumber = v; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String v) { this.accountName = v; }
    public OffsetDateTime getAccountVerifiedAt() { return accountVerifiedAt; }
    public void setAccountVerifiedAt(OffsetDateTime v) { this.accountVerifiedAt = v; }
    public String getKycStatus() { return kycStatus; }
    public void setKycStatus(String v) { this.kycStatus = v; }
    public OffsetDateTime getKycSubmittedAt() { return kycSubmittedAt; }
    public OffsetDateTime getKycReviewedAt() { return kycReviewedAt; }
    public UUID getKycReviewedBy() { return kycReviewedBy; }
    public String getKycRejectionReason() { return kycRejectionReason; }
    public String getKycCacDocumentUrl() { return kycCacDocumentUrl; }
    public void setKycCacDocumentUrl(String v) { this.kycCacDocumentUrl = v; }
    public String getKycDirectorIdUrl() { return kycDirectorIdUrl; }
    public void setKycDirectorIdUrl(String v) { this.kycDirectorIdUrl = v; }
    public String getKycUtilityBillUrl() { return kycUtilityBillUrl; }
    public void setKycUtilityBillUrl(String v) { this.kycUtilityBillUrl = v; }
    public String getKycBusinessAddress() { return kycBusinessAddress; }
    public void setKycBusinessAddress(String v) { this.kycBusinessAddress = v; }
    public String getImportapayMerchantId() { return importapayMerchantId; }
    public void setImportapayMerchantId(String v) { this.importapayMerchantId = v; }
    public String getRoutepayMerchantId() { return routepayMerchantId; }
    public void setRoutepayMerchantId(String v) { this.routepayMerchantId = v; }
    public String getPaystackSubaccountCode() { return paystackSubaccountCode; }
    public void setPaystackSubaccountCode(String v) { this.paystackSubaccountCode = v; }
    public boolean isPaymentsEnabled() { return paymentsEnabled; }
    public void setPaymentsEnabled(boolean v) { this.paymentsEnabled = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
