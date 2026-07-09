package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A scheduled viewing of a specific property. Extends the plain
 * "bookings" concept because outcome + party size + agent matter.
 */
@Entity
@Table(name = "property_viewings")
public class PropertyViewing {

    public static final String STATUS_SCHEDULED = "scheduled";
    public static final String STATUS_CONFIRMED = "confirmed";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_NO_SHOW = "no_show";
    public static final String STATUS_CANCELLED = "cancelled";

    public static final String OUTCOME_INTERESTED = "interested";
    public static final String OUTCOME_NOT_INTERESTED = "not_interested";
    public static final String OUTCOME_NEEDS_ANOTHER = "needs_another";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Column(name = "deal_id")
    private UUID dealId;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "prospect_name", nullable = false)
    private String prospectName;

    @Column(name = "prospect_phone")
    private String prospectPhone;

    @Column(name = "prospect_email")
    private String prospectEmail;

    @Column(name = "party_size", nullable = false)
    private int partySize = 1;

    @Column(name = "scheduled_at", nullable = false)
    private OffsetDateTime scheduledAt;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes = 30;

    @Column(nullable = false)
    private String status = STATUS_SCHEDULED;

    private String outcome;

    @Column(name = "outcome_notes")
    private String outcomeNotes;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected PropertyViewing() {
    }

    public PropertyViewing(UUID tenantId, UUID propertyId, String prospectName,
                           OffsetDateTime scheduledAt) {
        this.tenantId = tenantId;
        this.propertyId = propertyId;
        this.prospectName = prospectName;
        this.scheduledAt = scheduledAt;
    }

    public void confirm(OffsetDateTime at) {
        this.status = STATUS_CONFIRMED;
        this.confirmedAt = at;
    }

    public void cancel(OffsetDateTime at) {
        this.status = STATUS_CANCELLED;
        this.cancelledAt = at;
    }

    public void complete(String outcomeCode, String notes) {
        this.status = STATUS_COMPLETED;
        this.outcome = outcomeCode;
        this.outcomeNotes = notes;
    }

    public void markNoShow() {
        this.status = STATUS_NO_SHOW;
    }

    // ----- Accessors --------------------------------------------------------

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getPropertyId() { return propertyId; }
    public void setPropertyId(UUID propertyId) { this.propertyId = propertyId; }
    public UUID getDealId() { return dealId; }
    public void setDealId(UUID dealId) { this.dealId = dealId; }
    public UUID getAgentId() { return agentId; }
    public void setAgentId(UUID agentId) { this.agentId = agentId; }
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public String getProspectName() { return prospectName; }
    public void setProspectName(String prospectName) { this.prospectName = prospectName; }
    public String getProspectPhone() { return prospectPhone; }
    public void setProspectPhone(String prospectPhone) { this.prospectPhone = prospectPhone; }
    public String getProspectEmail() { return prospectEmail; }
    public void setProspectEmail(String prospectEmail) { this.prospectEmail = prospectEmail; }
    public int getPartySize() { return partySize; }
    public void setPartySize(int partySize) { this.partySize = partySize; }
    public OffsetDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(OffsetDateTime scheduledAt) { this.scheduledAt = scheduledAt; }
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    public String getStatus() { return status; }
    public String getOutcome() { return outcome; }
    public String getOutcomeNotes() { return outcomeNotes; }
    public OffsetDateTime getConfirmedAt() { return confirmedAt; }
    public OffsetDateTime getCancelledAt() { return cancelledAt; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
