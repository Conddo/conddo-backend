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
 * The universal payment intent. Every attempt to move money — subscription
 * charge, cart checkout, invoice pay-now, booking deposit, POS collection,
 * payment-link click — creates exactly one row.
 *
 * <p>The {@code provider} column routes to the right {@link
 * io.conddo.core.payments.PaymentProvider} implementation; {@code origin}
 * tells the webhook dispatcher which feature to fan back out to on success.
 */
@Entity
@Table(name = "payment_intents")
public class PaymentIntent {

    public static final String PROVIDER_PAYSTACK = "paystack";
    public static final String PROVIDER_IMPORTAPAY = "importapay";
    public static final String PROVIDER_ROUTEPAY = "routepay";

    public static final String ORIGIN_SUBSCRIPTION = "subscription";
    public static final String ORIGIN_ORDER = "order";
    public static final String ORIGIN_BOOKING = "booking";
    public static final String ORIGIN_INVOICE = "invoice";
    public static final String ORIGIN_POS = "pos";
    public static final String ORIGIN_LINK = "link";
    public static final String ORIGIN_OTHER = "other";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_EXPIRED = "expired";
    public static final String STATUS_REFUNDED = "refunded";
    public static final String STATUS_PARTIALLY_REFUNDED = "partially_refunded";

    @Id private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;

    @Column(nullable = false) private String provider;
    @Column(nullable = false) private String origin;

    @Column(name = "origin_order_id") private UUID originOrderId;
    @Column(name = "origin_booking_id") private UUID originBookingId;
    @Column(name = "origin_invoice_id") private UUID originInvoiceId;
    @Column(name = "origin_link_id") private UUID originLinkId;
    @Column(name = "origin_reference") private String originReference;

    @Column(nullable = false) private String currency = "NGN";
    @Column(name = "amount_kobo", nullable = false) private long amountKobo;
    @Column(name = "fee_kobo", nullable = false) private long feeKobo;
    @Column(name = "net_kobo", nullable = false) private long netKobo;

    @Column(name = "customer_id") private UUID customerId;
    @Column(name = "customer_name") private String customerName;
    @Column(name = "customer_email") private String customerEmail;
    @Column(name = "customer_phone") private String customerPhone;

    @Column(name = "provider_reference") private String providerReference;
    @Column(name = "checkout_url") private String checkoutUrl;
    @Column(name = "authorization_code") private String authorizationCode;

    // Bank-transfer PSPs (Importapay) hand us a static receiving account
    // instead of a checkout URL. Shown to the customer at pay time.
    @Column(name = "receiving_bank_name") private String receivingBankName;
    @Column(name = "receiving_account_number") private String receivingAccountNumber;
    @Column(name = "receiving_account_name") private String receivingAccountName;
    @Column(name = "sender_bank_name") private String senderBankName;
    @Column(name = "sender_account_number") private String senderAccountNumber;
    @Column(name = "matched_transaction_ref") private String matchedTransactionRef;

    @Column(nullable = false) private String status = STATUS_PENDING;
    @Column(name = "failure_reason") private String failureReason;

    @Column(name = "initiated_at", nullable = false) private OffsetDateTime initiatedAt;
    @Column(name = "completed_at") private OffsetDateTime completedAt;
    @Column(name = "last_verified_at") private OffsetDateTime lastVerifiedAt;

    @Column(name = "idempotency_key") private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false) private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = now;
        if (initiatedAt == null) initiatedAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() { updatedAt = OffsetDateTime.now(); }

    public void markSucceeded(long feeKobo, String providerReference) {
        this.status = STATUS_SUCCEEDED;
        this.feeKobo = feeKobo;
        this.netKobo = this.amountKobo - feeKobo;
        this.providerReference = providerReference;
        this.completedAt = OffsetDateTime.now();
        this.lastVerifiedAt = this.completedAt;
    }

    public void markFailed(String reason) {
        this.status = STATUS_FAILED;
        this.failureReason = reason;
        this.completedAt = OffsetDateTime.now();
        this.lastVerifiedAt = this.completedAt;
    }

    public void markExpired() {
        this.status = STATUS_EXPIRED;
        this.completedAt = OffsetDateTime.now();
    }

    public void markRefunded(boolean partial) {
        this.status = partial ? STATUS_PARTIALLY_REFUNDED : STATUS_REFUNDED;
        this.lastVerifiedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID v) { this.tenantId = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getOrigin() { return origin; }
    public void setOrigin(String v) { this.origin = v; }
    public UUID getOriginOrderId() { return originOrderId; }
    public void setOriginOrderId(UUID v) { this.originOrderId = v; }
    public UUID getOriginBookingId() { return originBookingId; }
    public void setOriginBookingId(UUID v) { this.originBookingId = v; }
    public UUID getOriginInvoiceId() { return originInvoiceId; }
    public void setOriginInvoiceId(UUID v) { this.originInvoiceId = v; }
    public UUID getOriginLinkId() { return originLinkId; }
    public void setOriginLinkId(UUID v) { this.originLinkId = v; }
    public String getOriginReference() { return originReference; }
    public void setOriginReference(String v) { this.originReference = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public long getAmountKobo() { return amountKobo; }
    public void setAmountKobo(long v) { this.amountKobo = v; }
    public long getFeeKobo() { return feeKobo; }
    public long getNetKobo() { return netKobo; }
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID v) { this.customerId = v; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String v) { this.customerName = v; }
    public String getCustomerEmail() { return customerEmail; }
    public void setCustomerEmail(String v) { this.customerEmail = v; }
    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String v) { this.customerPhone = v; }
    public String getProviderReference() { return providerReference; }
    public void setProviderReference(String v) { this.providerReference = v; }
    public String getCheckoutUrl() { return checkoutUrl; }
    public void setCheckoutUrl(String v) { this.checkoutUrl = v; }
    public String getAuthorizationCode() { return authorizationCode; }
    public void setAuthorizationCode(String v) { this.authorizationCode = v; }
    public String getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public OffsetDateTime getInitiatedAt() { return initiatedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public OffsetDateTime getLastVerifiedAt() { return lastVerifiedAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String v) { this.idempotencyKey = v; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String v) { this.metadata = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public String getReceivingBankName() { return receivingBankName; }
    public void setReceivingBankName(String v) { this.receivingBankName = v; }
    public String getReceivingAccountNumber() { return receivingAccountNumber; }
    public void setReceivingAccountNumber(String v) { this.receivingAccountNumber = v; }
    public String getReceivingAccountName() { return receivingAccountName; }
    public void setReceivingAccountName(String v) { this.receivingAccountName = v; }
    public String getSenderBankName() { return senderBankName; }
    public void setSenderBankName(String v) { this.senderBankName = v; }
    public String getSenderAccountNumber() { return senderAccountNumber; }
    public void setSenderAccountNumber(String v) { this.senderAccountNumber = v; }
    public String getMatchedTransactionRef() { return matchedTransactionRef; }
    public void setMatchedTransactionRef(String v) { this.matchedTransactionRef = v; }
}
