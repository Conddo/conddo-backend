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
 * Uploaded clinical document attached to a {@link PharmacyEmr}
 * (Pharmacy Roadmap Beta 4).
 */
@Entity
@Table(name = "pharmacy_emr_documents")
public class PharmacyEmrDocument {

    public static final String LAB_RESULT = "LAB_RESULT";
    public static final String PRESCRIPTION = "PRESCRIPTION";
    public static final String REFERRAL = "REFERRAL";
    public static final String IMAGING = "IMAGING";
    public static final String OTHER = "OTHER";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "emr_id", nullable = false)
    private UUID emrId;

    @Column(length = 150)
    private String label;

    @Column(name = "file_url", nullable = false, columnDefinition = "TEXT")
    private String fileUrl;

    @Column(name = "doc_type", nullable = false, length = 30)
    private String docType;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @CreationTimestamp
    @Column(name = "uploaded_at", updatable = false)
    private OffsetDateTime uploadedAt;

    protected PharmacyEmrDocument() {
    }

    public PharmacyEmrDocument(UUID tenantId, UUID emrId, String label, String fileUrl,
                                String docType, UUID uploadedBy) {
        this.tenantId = tenantId;
        this.emrId = emrId;
        this.label = label;
        this.fileUrl = fileUrl;
        this.docType = docType;
        this.uploadedBy = uploadedBy;
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

    public String getLabel() {
        return label;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public String getDocType() {
        return docType;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public OffsetDateTime getUploadedAt() {
        return uploadedAt;
    }
}
