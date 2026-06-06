package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A tenant's request for a creative service
 * (SOCIAL_AND_CREATIVE_SERVICES_SPEC §5). Status lifecycle:
 * {@code pending_payment → queued → in_progress → delivered}
 * ({@code cancelled} terminal at any point). {@code priceKobo} is frozen
 * at request time so catalog updates don't shift it.
 */
@Entity
@Table(name = "creative_service_requests")
public class CreativeServiceRequest {

    public static final String STATUS_PENDING_PAYMENT = "pending_payment";
    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_DELIVERED = "delivered";
    public static final String STATUS_CANCELLED = "cancelled";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "offering_id", nullable = false)
    private UUID offeringId;

    @Column(name = "social_post_id")
    private UUID socialPostId;

    @Column(nullable = false)
    private String brief;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attached_media")
    private List<UUID> attachedMedia;

    @Column(name = "price_kobo", nullable = false)
    private int priceKobo;

    @Column(nullable = false)
    private String status = STATUS_PENDING_PAYMENT;

    @Column(name = "payment_reference")
    private String paymentReference;

    @Column(name = "studio_job_id")
    private UUID studioJobId;

    @Column(name = "studio_job_number")
    private String studioJobNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "delivery_media")
    private List<Map<String, Object>> deliveryMedia;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected CreativeServiceRequest() {
    }

    public CreativeServiceRequest(UUID tenantId, UUID userId, UUID offeringId, UUID socialPostId,
                                  String brief, List<UUID> attachedMedia, int priceKobo) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.offeringId = offeringId;
        this.socialPostId = socialPostId;
        this.brief = brief;
        this.attachedMedia = attachedMedia;
        this.priceKobo = priceKobo;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getOfferingId() {
        return offeringId;
    }

    public UUID getSocialPostId() {
        return socialPostId;
    }

    public String getBrief() {
        return brief;
    }

    public List<UUID> getAttachedMedia() {
        return attachedMedia;
    }

    public int getPriceKobo() {
        return priceKobo;
    }

    public String getStatus() {
        return status;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public UUID getStudioJobId() {
        return studioJobId;
    }

    public String getStudioJobNumber() {
        return studioJobNumber;
    }

    public List<Map<String, Object>> getDeliveryMedia() {
        return deliveryMedia;
    }

    public OffsetDateTime getDeliveredAt() {
        return deliveredAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setPaymentReference(String paymentReference) {
        this.paymentReference = paymentReference;
    }

    /** Payment cleared — flip pending_payment → queued. Idempotent. */
    public void markPaid(String paymentReference) {
        this.paymentReference = paymentReference;
        if (STATUS_PENDING_PAYMENT.equals(this.status)) {
            this.status = STATUS_QUEUED;
        }
    }

    /** Studio accepted the job — pin its job id + number for the tenant's status pane. */
    public void markQueued(UUID studioJobId, String studioJobNumber) {
        this.studioJobId = studioJobId;
        this.studioJobNumber = studioJobNumber;
        if (STATUS_PENDING_PAYMENT.equals(this.status)) {
            this.status = STATUS_QUEUED;
        }
    }

    /** Studio reported in-progress (assigned + started). Used by the FE for the status pill. */
    public void markInProgress() {
        if (STATUS_QUEUED.equals(this.status)) {
            this.status = STATUS_IN_PROGRESS;
        }
    }

    /** Studio delivered the final media. Idempotent. */
    public void markDelivered(List<Map<String, Object>> deliveryMedia, OffsetDateTime at) {
        this.deliveryMedia = deliveryMedia;
        this.deliveredAt = at;
        this.status = STATUS_DELIVERED;
    }

    public void cancel() {
        this.status = STATUS_CANCELLED;
    }
}
