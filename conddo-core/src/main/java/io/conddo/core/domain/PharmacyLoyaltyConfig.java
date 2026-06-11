package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Per-tenant cashback configuration (Pharmacy Roadmap Beta 1). One
 * row per tenant — the {@code UNIQUE} on tenant_id makes the table a
 * key-value store.
 */
@Entity
@Table(name = "pharmacy_loyalty_config")
public class PharmacyLoyaltyConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, unique = true)
    private UUID tenantId;

    @Column(name = "cashback_rate", nullable = false)
    private BigDecimal cashbackRate = BigDecimal.valueOf(2);

    @Column(name = "min_redemption", nullable = false)
    private BigDecimal minRedemption = BigDecimal.valueOf(500);

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected PharmacyLoyaltyConfig() {
    }

    public PharmacyLoyaltyConfig(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public BigDecimal getCashbackRate() {
        return cashbackRate;
    }

    public BigDecimal getMinRedemption() {
        return minRedemption;
    }

    public boolean isActive() {
        return active;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setCashbackRate(BigDecimal cashbackRate) {
        this.cashbackRate = cashbackRate;
    }

    public void setMinRedemption(BigDecimal minRedemption) {
        this.minRedemption = minRedemption;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
