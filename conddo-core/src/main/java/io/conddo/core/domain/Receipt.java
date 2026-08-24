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
 * A tenant's receipt. Generated from a paid invoice — every receipt has
 * a source invoice (FK). Numbering is per-tenant sequential + year-scoped
 * (matches Nigerian retail convention of a separate receipt book).
 *
 * <p>Customer, amount, and payment method are snapshotted at generation
 * time so a later invoice edit doesn't rewrite receipt history.
 *
 * <p>Refunds are a status flip + amount, not a separate entity. A full
 * refund has {@code refundAmountKobo == amountKobo}.
 */
@Entity
@Table(name = "receipts")
public class Receipt {

    public static final String STATUS_ISSUED = "issued";
    public static final String STATUS_REFUNDED = "refunded";

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "receipt_number", nullable = false, updatable = false)
    private String receiptNumber;

    @Column(name = "customer_name", nullable = false) private String customerName;
    @Column(name = "customer_email") private String customerEmail;
    @Column(name = "customer_phone") private String customerPhone;

    @Column(nullable = false) private String currency = "NGN";
    @Column(name = "amount_kobo", nullable = false) private long amountKobo;

    @Column(name = "payment_method", nullable = false) private String paymentMethod;
    @Column(name = "payment_reference") private String paymentReference;

    @Column(name = "paid_at", nullable = false) private OffsetDateTime paidAt;
    @Column(name = "issued_at", nullable = false) private OffsetDateTime issuedAt;
    @Column private String notes;

    @Column(nullable = false) private String status = STATUS_ISSUED;
    @Column(name = "refund_amount_kobo") private long refundAmountKobo = 0;
    @Column(name = "refunded_at") private OffsetDateTime refundedAt;
    @Column(name = "refund_reason") private String refundReason;

    @Column(name = "last_sent_at") private OffsetDateTime lastSentAt;
    @Column(name = "last_sent_channel") private String lastSentChannel;

    @Column(name = "created_at", nullable = false, updatable = false) private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = now;
        if (issuedAt == null) issuedAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() { updatedAt = OffsetDateTime.now(); }

    /** Record a full or partial refund. Idempotent on the amount — passing
     *  the same amount twice doesn't compound. */
    public void refund(long amountKobo, String reason) {
        if (amountKobo <= 0) throw new IllegalArgumentException("Refund amount must be positive");
        if (amountKobo > this.amountKobo)
            throw new IllegalArgumentException("Refund amount exceeds receipt total");
        this.refundAmountKobo = amountKobo;
        this.refundReason = reason;
        this.refundedAt = OffsetDateTime.now();
        this.status = STATUS_REFUNDED;
    }

    /** Track a send event. Overwrites the previous timestamp/channel —
     *  we don't keep a full send log here. */
    public void markSent(String channel) {
        this.lastSentAt = OffsetDateTime.now();
        this.lastSentChannel = channel;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID v) { this.tenantId = v; }
    public UUID getInvoiceId() { return invoiceId; }
    public void setInvoiceId(UUID v) { this.invoiceId = v; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID v) { this.orderId = v; }
    public String getReceiptNumber() { return receiptNumber; }
    public void setReceiptNumber(String v) { this.receiptNumber = v; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String v) { this.customerName = v; }
    public String getCustomerEmail() { return customerEmail; }
    public void setCustomerEmail(String v) { this.customerEmail = v; }
    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String v) { this.customerPhone = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v == null || v.isBlank() ? "NGN" : v; }
    public long getAmountKobo() { return amountKobo; }
    public void setAmountKobo(long v) { this.amountKobo = v; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String v) { this.paymentMethod = v; }
    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String v) { this.paymentReference = v; }
    public OffsetDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(OffsetDateTime v) { this.paidAt = v; }
    public OffsetDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(OffsetDateTime v) { this.issuedAt = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }
    public String getStatus() { return status; }
    public long getRefundAmountKobo() { return refundAmountKobo; }
    public OffsetDateTime getRefundedAt() { return refundedAt; }
    public String getRefundReason() { return refundReason; }
    public OffsetDateTime getLastSentAt() { return lastSentAt; }
    public String getLastSentChannel() { return lastSentChannel; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
