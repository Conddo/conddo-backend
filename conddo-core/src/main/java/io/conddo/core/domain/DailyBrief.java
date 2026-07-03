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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One row per (tenant, date). Caches the AI-generated Daily Business Brief so
 * the second dashboard open of the day doesn't hit OpenRouter again.
 */
@Entity
@Table(name = "daily_briefs")
public class DailyBrief {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "brief_date", nullable = false, updatable = false)
    private LocalDate briefDate;

    @Column(nullable = false, length = 200)
    private String headline;

    @Column(nullable = false, length = 2000)
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> dataSnapshot;

    @CreationTimestamp
    @Column(name = "generated_at", updatable = false)
    private OffsetDateTime generatedAt;

    protected DailyBrief() {
    }

    public DailyBrief(UUID tenantId, LocalDate briefDate, String headline, String body,
                      Map<String, Object> dataSnapshot) {
        this.tenantId = tenantId;
        this.briefDate = briefDate;
        this.headline = headline;
        this.body = body;
        this.dataSnapshot = dataSnapshot;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public LocalDate getBriefDate() { return briefDate; }
    public String getHeadline() { return headline; }
    public String getBody() { return body; }
    public Map<String, Object> getDataSnapshot() { return dataSnapshot; }
    public OffsetDateTime getGeneratedAt() { return generatedAt; }
}
