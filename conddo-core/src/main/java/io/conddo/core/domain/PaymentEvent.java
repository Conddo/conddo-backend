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
 * Raw webhook event, persisted verbatim before any business logic runs.
 * Enables replay, idempotency (via unique {@code (provider, providerEventId)}),
 * and a defensible audit trail.
 */
@Entity
@Table(name = "payment_events")
public class PaymentEvent {

    @Id private UUID id;

    @Column(nullable = false) private String provider;
    @Column(name = "provider_event_id", nullable = false) private String providerEventId;
    @Column(name = "event_type", nullable = false) private String eventType;
    @Column(name = "payment_intent_id") private UUID paymentIntentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_body", nullable = false, columnDefinition = "jsonb")
    private String rawBody;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_headers", columnDefinition = "jsonb")
    private String rawHeaders;

    @Column(nullable = false) private boolean processed = false;
    @Column(name = "processed_at") private OffsetDateTime processedAt;
    @Column(name = "processing_error") private String processingError;
    @Column(nullable = false) private int attempts = 0;

    @Column(name = "received_at", nullable = false) private OffsetDateTime receivedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (receivedAt == null) receivedAt = OffsetDateTime.now();
    }

    public void markProcessed() {
        this.processed = true;
        this.processedAt = OffsetDateTime.now();
        this.processingError = null;
        this.attempts += 1;
    }

    public void markFailed(String error) {
        this.processed = false;
        this.processingError = error;
        this.attempts += 1;
    }

    public UUID getId() { return id; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getProviderEventId() { return providerEventId; }
    public void setProviderEventId(String v) { this.providerEventId = v; }
    public String getEventType() { return eventType; }
    public void setEventType(String v) { this.eventType = v; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public void setPaymentIntentId(UUID v) { this.paymentIntentId = v; }
    public String getRawBody() { return rawBody; }
    public void setRawBody(String v) { this.rawBody = v; }
    public String getRawHeaders() { return rawHeaders; }
    public void setRawHeaders(String v) { this.rawHeaders = v; }
    public boolean isProcessed() { return processed; }
    public OffsetDateTime getProcessedAt() { return processedAt; }
    public String getProcessingError() { return processingError; }
    public int getAttempts() { return attempts; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
}
