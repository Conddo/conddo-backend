package io.conddo.studio.platform;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row per Platform-Admin mutation (Infrastructure §23.4). Created in
 * Phase 13a so Phase 13b's mutators can write into it without a second
 * migration. GET endpoints intentionally do NOT audit — the platform's
 * existing {@code audit_log} already records request paths and the read
 * data is not sensitive (admin lists / details).
 */
@Entity
@Table(name = "platform_admin_audit_log")
public class PlatformAdminAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(nullable = false)
    private String action;

    @Column(name = "target_kind", nullable = false)
    private String targetKind;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private JsonNode before;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private JsonNode after;

    @Column
    private String correlation;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime at;

    protected PlatformAdminAudit() {
    }

    public PlatformAdminAudit(UUID actorId, String action, String targetKind, UUID targetId,
                              JsonNode before, JsonNode after, String correlation) {
        this.actorId = actorId;
        this.action = action;
        this.targetKind = targetKind;
        this.targetId = targetId;
        this.before = before;
        this.after = after;
        this.correlation = correlation;
    }

    public UUID getId() {
        return id;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public String getTargetKind() {
        return targetKind;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public JsonNode getBefore() {
        return before;
    }

    public JsonNode getAfter() {
        return after;
    }

    public String getCorrelation() {
        return correlation;
    }

    public OffsetDateTime getAt() {
        return at;
    }
}
