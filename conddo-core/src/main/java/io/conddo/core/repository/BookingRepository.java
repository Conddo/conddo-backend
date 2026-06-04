package io.conddo.core.repository;

import io.conddo.core.domain.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * RLS scopes every query to the current tenant, so these range queries already
 * see only this tenant's bookings — no manual tenant filter.
 */
public interface BookingRepository extends JpaRepository<Booking, UUID>, JpaSpecificationExecutor<Booking> {

    List<Booking> findByStartsAtBetweenOrderByStartsAt(OffsetDateTime from, OffsetDateTime to);

    List<Booking> findByStartsAtBetweenAndStatusNotOrderByStartsAt(
            OffsetDateTime from, OffsetDateTime to, String status);

    long countByStartsAtBetweenAndStatusNot(OffsetDateTime from, OffsetDateTime to, String status);

    @Query("select coalesce(sum(b.amount), 0) from Booking b "
            + "where b.startsAt >= :from and b.startsAt < :to and b.status <> :excludeStatus")
    BigDecimal sumAmountBetween(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                                @Param("excludeStatus") String excludeStatus);

    /**
     * Conflicts on a resource over a window. Half-open interval: two bookings
     * conflict when {@code A.starts < B.ends AND A.ends > B.starts}. {@code cancelled}
     * bookings don't count. Returns the list so the caller can include the
     * conflicting reference in the error message.
     */
    @Query("select b from Booking b "
            + "where b.resourceId = :resourceId "
            + "and b.status <> 'cancelled' "
            + "and b.startsAt < :endsAt and b.endsAt > :startsAt")
    List<Booking> findResourceConflicts(@Param("resourceId") UUID resourceId,
                                        @Param("startsAt") OffsetDateTime startsAt,
                                        @Param("endsAt") OffsetDateTime endsAt);
}
