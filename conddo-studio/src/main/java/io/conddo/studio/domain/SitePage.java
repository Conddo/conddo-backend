package io.conddo.studio.domain;

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
 * One page within a {@link Site} (§21.1). Sections live under it. {@code is_home}
 * is the landing page — every site needs exactly one; deleting it is refused
 * with a 422.
 */
@Entity
@Table(name = "site_pages")
public class SitePage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(nullable = false)
    private String slug;

    @Column(nullable = false)
    private String title;

    @Column(name = "is_home", nullable = false)
    private boolean home;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected SitePage() {
    }

    public SitePage(UUID siteId, String slug, String title, boolean home, int orderIndex) {
        this.siteId = siteId;
        this.slug = slug;
        this.title = title;
        this.home = home;
        this.orderIndex = orderIndex;
    }

    public void rename(String title) {
        if (title != null && !title.isBlank()) {
            this.title = title;
        }
    }

    public void resluggify(String slug) {
        if (slug != null && !slug.isBlank()) {
            this.slug = slug;
        }
    }

    public void setHome(boolean home) {
        this.home = home;
    }

    public void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSiteId() {
        return siteId;
    }

    public String getSlug() {
        return slug;
    }

    public String getTitle() {
        return title;
    }

    public boolean isHome() {
        return home;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
