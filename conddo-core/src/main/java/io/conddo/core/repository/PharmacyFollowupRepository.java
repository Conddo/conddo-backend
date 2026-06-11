package io.conddo.core.repository;

import io.conddo.core.domain.PharmacyFollowup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface PharmacyFollowupRepository extends JpaRepository<PharmacyFollowup, UUID>,
        JpaSpecificationExecutor<PharmacyFollowup> {

    @Query("select f from PharmacyFollowup f "
            + "where f.status = 'PENDING' and f.dueDate >= :from and f.dueDate <= :to "
            + "order by f.dueDate")
    List<PharmacyFollowup> findDueWithin(@Param("from") OffsetDateTime from,
                                          @Param("to") OffsetDateTime to);

    /**
     * Cross-tenant sweep — feeds the daily missed-sweep cron. Caller
     * binds {@code app.cross_tenant=true} before calling.
     */
    @Query("select f from PharmacyFollowup f "
            + "where f.status = 'PENDING' and f.dueDate < :cutoff")
    List<PharmacyFollowup> findPendingPastCutoff(@Param("cutoff") OffsetDateTime cutoff);
}
