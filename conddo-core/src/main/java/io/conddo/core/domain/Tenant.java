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
 * A business on Conddo.io. The tenant id is the isolation key for every
 * other table. Not itself tenant-scoped (managed by signup / super-admin).
 */
@Entity
@Table(name = "tenants")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "vertical_id")
    private String verticalId;

    @Column(name = "plan_id")
    private String planId;

    @Column(name = "custom_domain")
    private String customDomain;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected Tenant() {
    }

    public Tenant(String name, String slug, String verticalId, String planId) {
        this.name = name;
        this.slug = slug;
        this.verticalId = verticalId;
        this.planId = planId;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public String getVerticalId() {
        return verticalId;
    }

    public String getPlanId() {
        return planId;
    }

    public String getCustomDomain() {
        return customDomain;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
