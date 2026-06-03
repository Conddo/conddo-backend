package io.conddo.studio.domain;

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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One curated reference for the production team and the AI assistant
 * (Infrastructure §8). Brand palettes, section layouts, copy patterns, and
 * typography — keyed by vertical (or global when {@code vertical == null}).
 * Read by the AI generators to ground their suggestions; ADMIN-managed only.
 *
 * <p>Lives in the {@code studio} schema (admin reference data) rather than
 * {@code jobs} (transactional). {@link #content} is open-ended JSONB so the
 * shape can evolve per {@code kind} without a migration each time.
 */
@Entity
@Table(name = "design_standards")
public class DesignStandard {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Vertical id (e.g. "pharmacy"); null = applies across verticals. */
    @Column
    private String vertical;

    /** One of PALETTE / LAYOUT / COPY_PATTERN / TYPOGRAPHY. */
    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> content = new HashMap<>();

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected DesignStandard() {
    }

    public DesignStandard(String vertical, String kind, String name, String description,
                          Map<String, Object> content) {
        this.vertical = vertical;
        this.kind = kind;
        this.name = name;
        this.description = description;
        if (content != null) {
            this.content = content;
        }
    }

    public void rename(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    public void describe(String description) {
        if (description != null) {
            this.description = description;
        }
    }

    public void setVertical(String vertical) {
        this.vertical = vertical;   // null is meaningful (= global)
    }

    public void setContent(Map<String, Object> content) {
        if (content != null) {
            this.content = content;
        }
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public UUID getId() {
        return id;
    }

    public String getVertical() {
        return vertical;
    }

    public String getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getContent() {
        return content;
    }

    public boolean isActive() {
        return active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
