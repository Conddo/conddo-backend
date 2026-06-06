package io.conddo.core.repository;

import io.conddo.core.domain.BrandPackageSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** RLS-scoped; the webhook + renewal-cron paths use the {@code app.public_resolver} carve-out. */
public interface BrandPackageSubscriptionRepository extends JpaRepository<BrandPackageSubscription, UUID> {

    /** Current live subscription for the bound tenant — at most one. */
    @Query("""
            select s from BrandPackageSubscription s
            where s.status in ('pending_payment','active','past_due')
            order by s.createdAt desc
            """)
    Optional<BrandPackageSubscription> findCurrent();

    /** Webhook reconcile — cross-tenant lookup by RoutePay reference. */
    Optional<BrandPackageSubscription> findByPaymentReference(String paymentReference);

    /** Renewal-cron candidates: active subs whose current_period_end has passed. */
    @Query("""
            select s from BrandPackageSubscription s
            where s.status in ('active','past_due')
              and s.currentPeriodEnd < :now
            """)
    List<BrandPackageSubscription> findDueForRenewal(@Param("now") OffsetDateTime now);
}
