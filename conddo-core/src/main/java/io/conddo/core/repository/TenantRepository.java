package io.conddo.core.repository;

import io.conddo.core.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    boolean existsBySlug(String slug);

    Optional<Tenant> findBySlug(String slug);

    /** Resolves a tenant from its self-book link slug (§11.5 public endpoint). */
    Optional<Tenant> findByBookingLinkSlug(String bookingLinkSlug);

    // ----- Platform overview (admin dashboard on studio.getconddo.com) -----

    /** Total tenants across all statuses. */
    @Query("select count(t) from Tenant t")
    long countAll();

    /** Signups in the given window — for the "new this month" metric. */
    @Query("select count(t) from Tenant t where t.createdAt >= :since")
    long countCreatedSince(OffsetDateTime since);

    /** Tenants grouped by vertical for the pie/breakdown widget. */
    @Query("select coalesce(t.verticalId, 'unknown') as vertical, count(t) as count " +
            "from Tenant t group by t.verticalId order by count(t) desc")
    List<VerticalCount> countByVertical();

    interface VerticalCount {
        String getVertical();
        long getCount();
    }
}
