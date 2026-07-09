package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Real estate deal — a property + prospect pairing that moves through
 * a kanban pipeline. Nigerian real estate cadence: deposit-paid is the
 * pivotal moment when commission accrues, not signature.
 */
@Entity
@Table(name = "deals")
public class Deal {

    public static final String STAGE_LEAD = "lead";
    public static final String STAGE_VIEWING_SCHEDULED = "viewing_scheduled";
    public static final String STAGE_VIEWED = "viewed";
    public static final String STAGE_OFFER_MADE = "offer_made";
    public static final String STAGE_DEPOSIT_PAID = "deposit_paid";
    public static final String STAGE_DOCUMENTATION = "documentation";
    public static final String STAGE_SIGNED = "signed";
    public static final String STAGE_CLOSED = "closed";
    public static final String STAGE_LOST = "lost";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "property_id")
    private UUID propertyId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "prospect_name")
    private String prospectName;

    @Column(name = "prospect_phone")
    private String prospectPhone;

    @Column(name = "prospect_email")
    private String prospectEmail;

    @Column(nullable = false)
    private String stage = STAGE_LEAD;

    @Column(name = "stage_changed_at", nullable = false)
    private OffsetDateTime stageChangedAt = OffsetDateTime.now();

    @Column(name = "deal_value")
    private BigDecimal dealValue;

    @Column(nullable = false)
    private String currency = "NGN";

    @Column(name = "commission_pct")
    private BigDecimal commissionPct;

    @Column(name = "commission_amount")
    private BigDecimal commissionAmount;

    @Column(name = "deposit_amount")
    private BigDecimal depositAmount;

    @Column(name = "deposit_paid_at")
    private OffsetDateTime depositPaidAt;

    @Column(name = "primary_agent_id")
    private UUID primaryAgentId;

    @Column(name = "introducer_agent_id")
    private UUID introducerAgentId;

    private String notes;

    @Column(name = "lost_reason")
    private String lostReason;

    @Column(name = "expected_close_at")
    private OffsetDateTime expectedCloseAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected Deal() {
    }

    public Deal(UUID tenantId, String prospectName) {
        this.tenantId = tenantId;
        this.prospectName = prospectName;
    }

    // ----- Behaviors --------------------------------------------------------

    /** Move to a new stage + stamp timing. deposit_paid stage also stamps
     *  {@code depositPaidAt} if not already set. */
    public void moveToStage(String newStage, OffsetDateTime at) {
        this.stage = newStage;
        this.stageChangedAt = at;
        if (STAGE_DEPOSIT_PAID.equals(newStage) && this.depositPaidAt == null) {
            this.depositPaidAt = at;
        }
    }

    /** Recompute commission from {@code dealValue} × {@code commissionPct}.
     *  Called on mutate; keeps the denormalized amount in sync so the
     *  dashboard's sum(commission_amount) is a single indexed query. */
    public void recomputeCommission() {
        if (dealValue != null && commissionPct != null) {
            this.commissionAmount = dealValue
                    .multiply(commissionPct)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
    }

    public void markLost(String reason, OffsetDateTime at) {
        this.stage = STAGE_LOST;
        this.lostReason = reason;
        this.stageChangedAt = at;
    }

    // ----- Accessors --------------------------------------------------------

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getPropertyId() { return propertyId; }
    public void setPropertyId(UUID propertyId) { this.propertyId = propertyId; }
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public String getProspectName() { return prospectName; }
    public void setProspectName(String prospectName) { this.prospectName = prospectName; }
    public String getProspectPhone() { return prospectPhone; }
    public void setProspectPhone(String prospectPhone) { this.prospectPhone = prospectPhone; }
    public String getProspectEmail() { return prospectEmail; }
    public void setProspectEmail(String prospectEmail) { this.prospectEmail = prospectEmail; }
    public String getStage() { return stage; }
    public OffsetDateTime getStageChangedAt() { return stageChangedAt; }
    public BigDecimal getDealValue() { return dealValue; }
    public void setDealValue(BigDecimal dealValue) { this.dealValue = dealValue; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getCommissionPct() { return commissionPct; }
    public void setCommissionPct(BigDecimal commissionPct) { this.commissionPct = commissionPct; }
    public BigDecimal getCommissionAmount() { return commissionAmount; }
    public BigDecimal getDepositAmount() { return depositAmount; }
    public void setDepositAmount(BigDecimal depositAmount) { this.depositAmount = depositAmount; }
    public OffsetDateTime getDepositPaidAt() { return depositPaidAt; }
    public UUID getPrimaryAgentId() { return primaryAgentId; }
    public void setPrimaryAgentId(UUID primaryAgentId) { this.primaryAgentId = primaryAgentId; }
    public UUID getIntroducerAgentId() { return introducerAgentId; }
    public void setIntroducerAgentId(UUID introducerAgentId) { this.introducerAgentId = introducerAgentId; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getLostReason() { return lostReason; }
    public OffsetDateTime getExpectedCloseAt() { return expectedCloseAt; }
    public void setExpectedCloseAt(OffsetDateTime expectedCloseAt) { this.expectedCloseAt = expectedCloseAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
