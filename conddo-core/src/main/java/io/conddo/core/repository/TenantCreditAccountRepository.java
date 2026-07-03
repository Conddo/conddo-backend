package io.conddo.core.repository;

import io.conddo.core.domain.TenantCreditAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface TenantCreditAccountRepository extends JpaRepository<TenantCreditAccount, UUID> {

    Optional<TenantCreditAccount> findByTenantId(UUID tenantId);

    /**
     * Atomic sync consume — the critical race-safe query. Only decrements if
     * enough credits are available; returns the number of rows affected
     * (1 on success, 0 when the tenant is out of credits or the cycle has
     * rolled over and needs a reset first).
     *
     * <p>Callers MUST check for a zero return and fall back to the caller-
     * side rollover-then-retry path when zero. This is the only way to
     * combine the availability check + decrement in a single UPDATE so that
     * two concurrent consumers can't both succeed against a shared pool.
     */
    @Modifying
    @Query(value = """
            UPDATE tenant_credit_accounts
               SET credits_used = credits_used + :cost,
                   updated_at   = NOW()
             WHERE tenant_id = :tenantId
               AND (monthly_quota + topup_credits - credits_used - reserved_credits) >= :cost
               AND billing_cycle_end > :now
            """, nativeQuery = true)
    int consumeIfAvailable(@Param("tenantId") UUID tenantId,
                           @Param("cost") int cost,
                           @Param("now") OffsetDateTime now);

    /**
     * Atomic reserve — bumps {@code reserved_credits} against available
     * headroom. Same success/zero semantics as {@link #consumeIfAvailable}.
     */
    @Modifying
    @Query(value = """
            UPDATE tenant_credit_accounts
               SET reserved_credits = reserved_credits + :cost,
                   updated_at       = NOW()
             WHERE tenant_id = :tenantId
               AND (monthly_quota + topup_credits - credits_used - reserved_credits) >= :cost
               AND billing_cycle_end > :now
            """, nativeQuery = true)
    int reserveIfAvailable(@Param("tenantId") UUID tenantId,
                           @Param("cost") int cost,
                           @Param("now") OffsetDateTime now);

    /** Confirm a reservation: move :cost from reserved to consumed. */
    @Modifying
    @Query(value = """
            UPDATE tenant_credit_accounts
               SET reserved_credits = reserved_credits - :cost,
                   credits_used     = credits_used + :cost,
                   updated_at       = NOW()
             WHERE tenant_id = :tenantId
               AND reserved_credits >= :cost
            """, nativeQuery = true)
    int confirmReservation(@Param("tenantId") UUID tenantId, @Param("cost") int cost);

    /** Release a reservation: return :cost to available pool. */
    @Modifying
    @Query(value = """
            UPDATE tenant_credit_accounts
               SET reserved_credits = reserved_credits - :cost,
                   updated_at       = NOW()
             WHERE tenant_id = :tenantId
               AND reserved_credits >= :cost
            """, nativeQuery = true)
    int releaseReservation(@Param("tenantId") UUID tenantId, @Param("cost") int cost);

    /**
     * Roll the billing cycle forward: reset credits_used, advance the cycle
     * window. Only fires when the current end is in the past — idempotent
     * if two threads race the rollover (only one row-match).
     */
    @Modifying
    @Query(value = """
            UPDATE tenant_credit_accounts
               SET credits_used        = 0,
                   billing_cycle_start = :now,
                   billing_cycle_end   = :nextEnd,
                   updated_at          = NOW()
             WHERE tenant_id = :tenantId
               AND billing_cycle_end <= :now
            """, nativeQuery = true)
    int rollCycle(@Param("tenantId") UUID tenantId,
                  @Param("now") OffsetDateTime now,
                  @Param("nextEnd") OffsetDateTime nextEnd);
}
