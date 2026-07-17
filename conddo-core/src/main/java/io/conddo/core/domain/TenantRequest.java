package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A tenant-submitted support entry — feature request, complaint, bug, or
 * question. Backed by {@code tenant_requests} (V70). Tenant-scoped by RLS.
 *
 * <p>Kept lean by design. Multi-turn threading, attachments, upvotes, and
 * SLA fields all belong on follow-up columns / tables once the volume
 * justifies them.
 */
@Entity
@Table(name = "tenant_requests")
public class TenantRequest {

    public static final String KIND_FEATURE   = "FEATURE";
    public static final String KIND_COMPLAINT = "COMPLAINT";
    public static final String KIND_BUG       = "BUG";
    public static final String KIND_QUESTION  = "QUESTION";

    public static final String STATUS_OPEN         = "OPEN";
    public static final String STATUS_IN_PROGRESS  = "IN_PROGRESS";
    public static final String STATUS_RESOLVED     = "RESOLVED";
    public static final String STATUS_DISMISSED    = "DISMISSED";

    public static final String PRIORITY_LOW    = "LOW";
    public static final String PRIORITY_NORMAL = "NORMAL";
    public static final String PRIORITY_HIGH   = "HIGH";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @Column(nullable = false)
    private String status = STATUS_OPEN;

    @Column(nullable = false)
    private String priority = PRIORITY_NORMAL;

    @Column(name = "admin_response")
    private String adminResponse;

    @Column(name = "responded_by")
    private UUID respondedBy;

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected TenantRequest() {}

    public TenantRequest(UUID tenantId, UUID createdBy, String kind, String title, String body) {
        this.tenantId = tenantId;
        this.createdBy = createdBy;
        this.kind = kind;
        this.title = title;
        this.body = body;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    /** Admin-side reply. Sets response body, staff attribution, and timestamp
     *  atomically. Status is set separately so the admin can reply without
     *  closing, or close without replying. */
    public void applyResponse(String response, UUID staffId, OffsetDateTime at) {
        this.adminResponse = response;
        this.respondedBy = staffId;
        this.respondedAt = at;
    }

    public void changeStatus(String status)   { this.status = status; }
    public void changePriority(String value)  { this.priority = value; }

    // ----- getters ---------------------------------------------------------

    public UUID getId()                     { return id; }
    public UUID getTenantId()               { return tenantId; }
    public UUID getCreatedBy()              { return createdBy; }
    public String getKind()                 { return kind; }
    public String getTitle()                { return title; }
    public String getBody()                 { return body; }
    public String getStatus()               { return status; }
    public String getPriority()             { return priority; }
    public String getAdminResponse()        { return adminResponse; }
    public UUID getRespondedBy()            { return respondedBy; }
    public OffsetDateTime getRespondedAt()  { return respondedAt; }
    public OffsetDateTime getCreatedAt()    { return createdAt; }
    public OffsetDateTime getUpdatedAt()    { return updatedAt; }
}
