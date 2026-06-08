package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A delivery address saved by a customer on the merchant's public
 * pharmacy website (PHARMACY_PUBLIC_API_SPEC §7). Tenant-scoped via RLS
 * and additionally filtered by {@code customer_id} in service code.
 */
@Entity
@Table(name = "customer_addresses")
public class CustomerAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    private String label;

    @Column(nullable = false)
    private String street;

    private String city;

    @Column(nullable = false)
    private String state;

    private String landmark;

    @Column(name = "is_default", nullable = false)
    private boolean defaultAddress = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected CustomerAddress() {
    }

    public CustomerAddress(UUID tenantId, UUID customerId, String label, String street,
                           String city, String state, String landmark, boolean defaultAddress) {
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.label = label;
        this.street = street;
        this.city = city;
        this.state = state;
        this.landmark = landmark;
        this.defaultAddress = defaultAddress;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getLabel() {
        return label;
    }

    public String getStreet() {
        return street;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public String getLandmark() {
        return landmark;
    }

    public boolean isDefaultAddress() {
        return defaultAddress;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setDefaultAddress(boolean defaultAddress) {
        this.defaultAddress = defaultAddress;
    }
}
