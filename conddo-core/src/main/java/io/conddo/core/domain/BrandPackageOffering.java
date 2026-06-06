package io.conddo.core.domain;

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
import java.util.Map;
import java.util.UUID;

/**
 * Studio-admin-managed catalog of monthly creative bundles
 * (SOCIAL_AND_CREATIVE_SERVICES_SPEC §6). Not tenant-scoped. {@code includes}
 * keys must match {@link CreativeServiceOffering#getCode()} so quota
 * lookups don't drift.
 */
@Entity
@Table(name = "brand_package_offerings")
public class BrandPackageOffering {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "monthly_price_kobo", nullable = false)
    private int monthlyPriceKobo;

    /** {@code {offeringCode: count}} — quota per creative-service offering. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Integer> includes;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected BrandPackageOffering() {
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public int getMonthlyPriceKobo() {
        return monthlyPriceKobo;
    }

    public Map<String, Integer> getIncludes() {
        return includes;
    }

    public boolean isActive() {
        return active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
