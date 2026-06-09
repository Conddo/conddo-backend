package io.conddo.core.repository;

import io.conddo.core.domain.PharmacyDiscount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PharmacyDiscountRepository extends JpaRepository<PharmacyDiscount, UUID>,
        JpaSpecificationExecutor<PharmacyDiscount> {

    /**
     * The currently-active discount for a product, if any — APPROVED
     * with now within {@code [starts_at, ends_at]}. There should only
     * ever be one (the service prevents overlapping APPROVED rows on
     * the same product), so {@code findFirst} is safe.
     */
    @Query("select d from PharmacyDiscount d where d.productId = :productId "
            + "and d.status = 'APPROVED' "
            + "and d.startsAt <= :now "
            + "and (d.endsAt is null or d.endsAt >= :now) "
            + "order by d.startsAt desc")
    Optional<PharmacyDiscount> findActiveForProduct(@Param("productId") UUID productId,
                                                    @Param("now") OffsetDateTime now);

    /** APPROVED rows whose ends_at has passed — the expiry sweeper flips them to EXPIRED. */
    @Query("select d from PharmacyDiscount d where d.status = 'APPROVED' "
            + "and d.endsAt is not null and d.endsAt < :now")
    List<PharmacyDiscount> findExpiredButNotYetSwept(@Param("now") OffsetDateTime now);
}
