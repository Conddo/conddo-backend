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
 * One plan in the catalog (Pricing v2, V67). Five rows: {@code free},
 * {@code student}, {@code starter}, {@code growth}, {@code pro}. Prices in
 * <b>Kobo</b> on the wire to the DB; the wire shape to the FE is in
 * <b>Naira</b> — converted in the response builder.
 *
 * <p>Every tier now has a real yearly price (30% off vs 12× monthly); the
 * legacy {@code custom} escape hatch is unused after V67 (custom-priced
 * enterprise sits above the catalogue anyway).
 */
@Entity
@Table(name = "subscription_plans")
public class SubscriptionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Canonical plan name — {@code free} / {@code student} / {@code starter}
     *  / {@code growth} / {@code pro}. */
    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "monthly_price")
    private Integer monthlyPrice;

    @Column(name = "quarterly_price")
    private Integer quarterlyPrice;

    /** Kobo. Nullable for parity with the other two, but set for every
     *  tier from V67 onward — a null here indicates "yearly cycle not
     *  offered on this plan" rather than "come back later". */
    @Column(name = "yearly_price")
    private Integer yearlyPrice;

    @Column(name = "is_custom", nullable = false)
    private boolean custom = false;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected SubscriptionPlan() {
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Integer getMonthlyPrice() {
        return monthlyPrice;
    }

    public Integer getQuarterlyPrice() {
        return quarterlyPrice;
    }

    public Integer getYearlyPrice() {
        return yearlyPrice;
    }

    public boolean isCustom() {
        return custom;
    }

    public boolean isActive() {
        return active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
