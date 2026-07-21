package io.conddo.core.repository;

import io.conddo.core.domain.Payout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PayoutRepository extends JpaRepository<Payout, UUID> {

    Optional<Payout> findByProviderAndProviderReference(String provider, String providerReference);

    List<Payout> findByTenantIdOrderByInitiatedAtDesc(UUID tenantId);
}
