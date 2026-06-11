package io.conddo.core.domain;

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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Electronic Medical Record for one customer (Pharmacy Roadmap Beta
 * 4). Demographics live as scalar columns; the three list-shaped
 * fields (allergies / chronic conditions / immunizations) are JSONB
 * arrays of free-form objects to keep the schema flat while the
 * clinical model evolves.
 */
@Entity
@Table(name = "pharmacy_emr")
public class PharmacyEmr {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "blood_group", length = 5)
    private String bloodGroup;

    @Column(length = 5)
    private String genotype;

    @Column(name = "height_cm")
    private BigDecimal heightCm;

    @Column(name = "weight_kg")
    private BigDecimal weightKg;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSONB")
    private List<Map<String, Object>> allergies = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "chronic_conditions", nullable = false, columnDefinition = "JSONB")
    private List<Map<String, Object>> chronicConditions = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSONB")
    private List<Map<String, Object>> immunizations = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected PharmacyEmr() {
    }

    public PharmacyEmr(UUID tenantId, UUID customerId) {
        this.tenantId = tenantId;
        this.customerId = customerId;
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

    public String getBloodGroup() {
        return bloodGroup;
    }

    public String getGenotype() {
        return genotype;
    }

    public BigDecimal getHeightCm() {
        return heightCm;
    }

    public BigDecimal getWeightKg() {
        return weightKg;
    }

    public List<Map<String, Object>> getAllergies() {
        return allergies;
    }

    public List<Map<String, Object>> getChronicConditions() {
        return chronicConditions;
    }

    public List<Map<String, Object>> getImmunizations() {
        return immunizations;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setBloodGroup(String bloodGroup) {
        this.bloodGroup = bloodGroup;
    }

    public void setGenotype(String genotype) {
        this.genotype = genotype;
    }

    public void setHeightCm(BigDecimal heightCm) {
        this.heightCm = heightCm;
    }

    public void setWeightKg(BigDecimal weightKg) {
        this.weightKg = weightKg;
    }

    public void setAllergies(List<Map<String, Object>> allergies) {
        this.allergies = allergies == null ? new ArrayList<>() : allergies;
    }

    public void setChronicConditions(List<Map<String, Object>> chronicConditions) {
        this.chronicConditions = chronicConditions == null ? new ArrayList<>() : chronicConditions;
    }

    public void setImmunizations(List<Map<String, Object>> immunizations) {
        this.immunizations = immunizations == null ? new ArrayList<>() : immunizations;
    }
}
