package io.conddo.studio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builder root for one website-build job (Infrastructure §21.1). 1:1 with
 * {@link Job}. Holds theme + meta JSONB plus the publish state; pages and
 * sections are children. {@code @Version} powers optimistic locking — every
 * mutation increments it and the FE sends {@code If-Match: <version>} to
 * detect concurrent edits.
 */
@Entity
@Table(name = "sites")
public class Site {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false, unique = true)
    private UUID jobId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> theme = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> meta = new HashMap<>();

    @Column(nullable = false)
    private String status = "DRAFT";

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Version
    @Column(nullable = false)
    private Integer version = 1;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected Site() {
    }

    public Site(UUID jobId) {
        this.jobId = jobId;
    }

    public void setTheme(Map<String, Object> theme) {
        if (theme != null) {
            this.theme = new HashMap<>(theme);
        }
    }

    public void mergeTheme(Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        if (this.theme == null) {
            this.theme = new HashMap<>();
        }
        this.theme.putAll(updates);
    }

    public void setMeta(Map<String, Object> meta) {
        if (meta != null) {
            this.meta = new HashMap<>(meta);
        }
    }

    /** Mark PUBLISHED at the given instant. Idempotent — second publish is a no-op. */
    public void publish(OffsetDateTime at) {
        if (!"PUBLISHED".equals(this.status)) {
            this.status = "PUBLISHED";
            this.publishedAt = at;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getJobId() {
        return jobId;
    }

    public Map<String, Object> getTheme() {
        return theme;
    }

    public Map<String, Object> getMeta() {
        return meta;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public Integer getVersion() {
        return version;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
