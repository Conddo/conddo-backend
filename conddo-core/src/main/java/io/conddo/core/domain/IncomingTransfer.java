package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The money feed — one row per inbound transfer / terminal sale from a
 * connected account (Moniepoint / OPay). Tenant payments land in the
 * tenant's OWN account; this row is Conddo's view of that money so the
 * tenant can match it to an invoice / order.
 *
 * <p>{@code providerReference} is the provider's own transaction id,
 * unique per provider — deduped so webhook retries and poller runs
 * can't double-insert. {@code status} is {@code unmatched} until the
 * tenant confirms the match ({@link #markMatched}), which flips the
 * linked invoice to paid server-side.
 */
@Entity
@Table(name = "incoming_transfers")
public class IncomingTransfer {

    public static final String STATUS_UNMATCHED = "unmatched";
    public static final String STATUS_MATCHED = "matched";

    @Id
    @Column(name = "id", updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_reference", nullable = false)
    private String providerReference;

    @Column(name = "sender_name")
    private String senderName;

    @Column(name = "sender_account_number")
    private String senderAccountNumber;

    @Column(name = "sender_bank")
    private String senderBank;

    @Column(name = "amount_kobo", nullable = false)
    private long amountKobo;

    @Column(nullable = false)
    private String currency = "NGN";

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(nullable = false)
    private String status = STATUS_UNMATCHED;

    @Column(name = "matched_invoice_id")
    private UUID matchedInvoiceId;

    @Column(name = "matched_order_id")
    private UUID matchedOrderId;

    @Column(name = "matched_at")
    private OffsetDateTime matchedAt;

    @Column(name = "matched_by")
    private UUID matchedBy;

    @Column
    private String note;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private String rawPayload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected IncomingTransfer() {
    }

    public IncomingTransfer(UUID tenantId, String provider, String providerReference,
                            long amountKobo, String currency, OffsetDateTime receivedAt) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.provider = provider;
        this.providerReference = providerReference;
        this.amountKobo = amountKobo;
        this.currency = currency == null ? "NGN" : currency;
        this.receivedAt = receivedAt;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getProvider() { return provider; }
    public String getProviderReference() { return providerReference; }
    public String getSenderName() { return senderName; }
    public void setSenderName(String v) { this.senderName = v; }
    public String getSenderAccountNumber() { return senderAccountNumber; }
    public void setSenderAccountNumber(String v) { this.senderAccountNumber = v; }
    public String getSenderBank() { return senderBank; }
    public void setSenderBank(String v) { this.senderBank = v; }
    public long getAmountKobo() { return amountKobo; }
    public void setAmountKobo(long v) { this.amountKobo = v; }
    public String getCurrency() { return currency; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(OffsetDateTime v) { this.receivedAt = v; }
    public String getStatus() { return status; }
    public UUID getMatchedInvoiceId() { return matchedInvoiceId; }
    public UUID getMatchedOrderId() { return matchedOrderId; }
    public OffsetDateTime getMatchedAt() { return matchedAt; }
    public UUID getMatchedBy() { return matchedBy; }
    public String getNote() { return note; }
    public String getRawPayload() { return rawPayload; }
    public void setRawPayload(String v) { this.rawPayload = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    /**
     * Confirm the match. Idempotent by construction — callers check
     * {@code status} first and return the existing match; a second call
     * with the same target is a no-op.
     */
    public void markMatched(UUID invoiceId, UUID orderId, UUID matchedBy, String note) {
        this.status = STATUS_MATCHED;
        this.matchedInvoiceId = invoiceId;
        this.matchedOrderId = orderId;
        this.matchedAt = OffsetDateTime.now();
        this.matchedBy = matchedBy;
        this.note = note;
    }
}
