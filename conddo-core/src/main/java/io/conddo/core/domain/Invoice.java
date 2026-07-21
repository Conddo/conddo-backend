package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A tenant's invoice / receipt. One entity for both lifecycle states —
 * unpaid rows are "invoices", paid rows are "receipts". Field intent:
 *
 * <ul>
 *   <li>{@code invoiceNumber} is the tenant-facing identifier, e.g.
 *       {@code "INV-2026-0001"}. Per-tenant sequential + year-scoped
 *       (Nigerian accounting practice resets yearly). Filled by
 *       {@link io.conddo.core.service.InvoiceService} on create.</li>
 *   <li>Customer info is denormalised on purpose — the customer name +
 *       email must be preserved on the invoice even if the CRM record is
 *       later updated or deleted.</li>
 *   <li>All money is in kobo (₦1 = 100 kobo), same as the rest of the
 *       codebase.</li>
 *   <li>{@code publicToken} unguessable string that lets a customer view
 *       the invoice at {@code /i/{token}} without a login.</li>
 * </ul>
 */
@Entity
@Table(name = "invoices")
public class Invoice {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_PAID = "paid";
    public static final String STATUS_OVERDUE = "overdue";
    public static final String STATUS_VOID = "void";

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "invoice_number", nullable = false, updatable = false)
    private String invoiceNumber;

    @Column(name = "linked_order_id")
    private UUID linkedOrderId;

    @Column(name = "linked_booking_id")
    private UUID linkedBookingId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "customer_email")
    private String customerEmail;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "customer_address")
    private String customerAddress;

    @Column(nullable = false)
    private String currency = "NGN";

    @Column(name = "subtotal_kobo", nullable = false)
    private long subtotalKobo;

    @Column(name = "tax_kobo", nullable = false)
    private long taxKobo;

    @Column(name = "discount_kobo", nullable = false)
    private long discountKobo;

    @Column(name = "total_kobo", nullable = false)
    private long totalKobo;

    @Column(nullable = false)
    private String status = STATUS_DRAFT;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate = LocalDate.now();

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "paid_method")
    private String paidMethod;

    @Column(name = "payment_reference")
    private String paymentReference;

    @Column
    private String notes;

    @Column
    private String terms;

    @Column(name = "public_token", nullable = false, updatable = false, unique = true)
    private String publicToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Invoice() {}

    public Invoice(UUID tenantId, String invoiceNumber, String customerName, String publicToken) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.invoiceNumber = invoiceNumber;
        this.customerName = customerName;
        this.publicToken = publicToken;
    }

    @PrePersist
    void onPersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (issueDate == null) issueDate = LocalDate.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /** Recompute totals from a supplied line breakdown. Kept on the entity
     *  so tenant + admin edit paths both go through the same math. */
    public void applyTotals(long subtotalKobo, long taxKobo, long discountKobo) {
        this.subtotalKobo = subtotalKobo;
        this.taxKobo = taxKobo;
        this.discountKobo = discountKobo;
        this.totalKobo = subtotalKobo + taxKobo - discountKobo;
    }

    public void markSent() {
        if (STATUS_DRAFT.equals(status)) {
            status = STATUS_SENT;
        }
    }

    /** Manual mark-paid — used for cash / transfer payments the tenant
     *  confirmed off-platform. Method is one of cash / transfer / other. */
    public void markPaidManually(String method, OffsetDateTime at) {
        status = STATUS_PAID;
        paidAt = at;
        paidMethod = method;
    }

    /** Gateway mark-paid — flipped by an Importapay / Routepay webhook
     *  after the customer completes payment via the invoice's Pay Now
     *  button. Stores the gateway reference for reconciliation. */
    public void markPaidByGateway(String gatewayMethod, String gatewayReference, OffsetDateTime at) {
        status = STATUS_PAID;
        paidAt = at;
        paidMethod = gatewayMethod;
        paymentReference = gatewayReference;
    }

    public void voidInvoice() {
        status = STATUS_VOID;
    }

    // ----- getters + setters ----------------------------------------------

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public UUID getLinkedOrderId() { return linkedOrderId; }
    public UUID getLinkedBookingId() { return linkedBookingId; }
    public UUID getCustomerId() { return customerId; }
    public String getCustomerName() { return customerName; }
    public String getCustomerEmail() { return customerEmail; }
    public String getCustomerPhone() { return customerPhone; }
    public String getCustomerAddress() { return customerAddress; }
    public String getCurrency() { return currency; }
    public long getSubtotalKobo() { return subtotalKobo; }
    public long getTaxKobo() { return taxKobo; }
    public long getDiscountKobo() { return discountKobo; }
    public long getTotalKobo() { return totalKobo; }
    public String getStatus() { return status; }
    public LocalDate getIssueDate() { return issueDate; }
    public LocalDate getDueDate() { return dueDate; }
    public OffsetDateTime getPaidAt() { return paidAt; }
    public String getPaidMethod() { return paidMethod; }
    public String getPaymentReference() { return paymentReference; }
    public String getNotes() { return notes; }
    public String getTerms() { return terms; }
    public String getPublicToken() { return publicToken; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setLinkedOrderId(UUID linkedOrderId) { this.linkedOrderId = linkedOrderId; }
    public void setLinkedBookingId(UUID linkedBookingId) { this.linkedBookingId = linkedBookingId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }
    public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }
    public void setCustomerAddress(String customerAddress) { this.customerAddress = customerAddress; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public void setNotes(String notes) { this.notes = notes; }
    public void setTerms(String terms) { this.terms = terms; }
    public void setIssueDate(LocalDate issueDate) { this.issueDate = issueDate; }
}
