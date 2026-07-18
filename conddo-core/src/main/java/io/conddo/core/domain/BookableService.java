package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row in a tenant's public booking menu (V72). Every field except
 * {@code name} + {@code durationMinutes} is optional; a tenant can spin
 * up a service in seconds and refine later.
 *
 * <p>Not confused with Spring {@code @Service} — this is the business
 * offering (haircut, tuning, consultation) that a customer picks on the
 * public /book page. Sorting on the FE uses {@code sortOrder} first, then
 * {@code name} alphabetically as a stable tiebreak.
 */
@Entity
@Table(name = "bookable_services")
public class BookableService {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "price_kobo", nullable = false)
    private long priceKobo;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected BookableService() {}

    public BookableService(UUID tenantId, String name, int durationMinutes) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.name = name;
        this.durationMinutes = durationMinutes;
    }

    @PrePersist
    void onPersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (this.createdAt == null) this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    // ----- getters + setters ----------------------------------------------

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getDurationMinutes() { return durationMinutes; }
    public long getPriceKobo() { return priceKobo; }
    public boolean isActive() { return active; }
    public int getSortOrder() { return sortOrder; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    public void setPriceKobo(long priceKobo) { this.priceKobo = priceKobo; }
    public void setActive(boolean active) { this.active = active; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
