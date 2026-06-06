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
 * Studio-admin-managed catalog of creative services tenants can buy
 * (SOCIAL_AND_CREATIVE_SERVICES_SPEC §5). Not tenant-scoped — every
 * tenant sees the same catalog. {@code jobType} maps to Studio's
 * job-type registry (CREATIVE_DESIGN / CREATIVE_VIDEO / CREATIVE_AD) so
 * the request hand-off can route to the right team.
 */
@Entity
@Table(name = "creative_service_offerings")
public class CreativeServiceOffering {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "price_kobo", nullable = false)
    private int priceKobo;

    @Column(name = "turnaround_hours", nullable = false)
    private int turnaroundHours;

    @Column(name = "job_type", nullable = false)
    private String jobType;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected CreativeServiceOffering() {
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

    public int getPriceKobo() {
        return priceKobo;
    }

    public int getTurnaroundHours() {
        return turnaroundHours;
    }

    public String getJobType() {
        return jobType;
    }

    public boolean isActive() {
        return active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
