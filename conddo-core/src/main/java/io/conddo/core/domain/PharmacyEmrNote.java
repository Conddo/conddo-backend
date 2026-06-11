package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable clinical note appended to a {@link PharmacyEmr} (Pharmacy
 * Roadmap Beta 4). No setters, no UPDATE/DELETE handlers at the API
 * layer.
 */
@Entity
@Table(name = "pharmacy_emr_notes")
public class PharmacyEmrNote {

    public static final String CLINICAL = "CLINICAL";
    public static final String ALLERGY = "ALLERGY";
    public static final String COUNSELLING = "COUNSELLING";
    public static final String REFERRAL = "REFERRAL";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "emr_id", nullable = false)
    private UUID emrId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String note;

    @Column(name = "note_type", nullable = false, length = 30)
    private String noteType;

    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected PharmacyEmrNote() {
    }

    public PharmacyEmrNote(UUID tenantId, UUID emrId, String note, String noteType, UUID createdBy) {
        this.tenantId = tenantId;
        this.emrId = emrId;
        this.note = note;
        this.noteType = noteType;
        this.createdBy = createdBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getEmrId() {
        return emrId;
    }

    public String getNote() {
        return note;
    }

    public String getNoteType() {
        return noteType;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
