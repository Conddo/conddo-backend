package io.conddo.studio.domain;

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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One typed content block on a {@link SitePage} (§21.4). The {@code content}
 * JSONB shape depends on {@code section_type} — validated server-side at write
 * time by {@code SectionContentValidator}. Type is immutable once set; to
 * change type, delete + create.
 */
@Entity
@Table(name = "site_sections")
public class SiteSection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "page_id", nullable = false)
    private UUID pageId;

    @Column(name = "section_type", nullable = false)
    private String sectionType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> content = new HashMap<>();

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected SiteSection() {
    }

    public SiteSection(UUID pageId, String sectionType, Map<String, Object> content, int orderIndex) {
        this.pageId = pageId;
        this.sectionType = sectionType;
        if (content != null) {
            this.content = new HashMap<>(content);
        }
        this.orderIndex = orderIndex;
    }

    public void setContent(Map<String, Object> content) {
        if (content != null) {
            this.content = new HashMap<>(content);
        }
    }

    public void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPageId() {
        return pageId;
    }

    public String getSectionType() {
        return sectionType;
    }

    public Map<String, Object> getContent() {
        return content;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
