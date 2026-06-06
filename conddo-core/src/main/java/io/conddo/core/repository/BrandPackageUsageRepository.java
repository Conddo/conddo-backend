package io.conddo.core.repository;

import io.conddo.core.domain.BrandPackageUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface BrandPackageUsageRepository extends JpaRepository<BrandPackageUsage, UUID> {

    Optional<BrandPackageUsage> findBySubscriptionIdAndPeriodStart(UUID subscriptionId, OffsetDateTime periodStart);
}
