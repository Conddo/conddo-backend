package io.conddo.core.repository;

import io.conddo.core.domain.PharmacyLoyaltyConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PharmacyLoyaltyConfigRepository extends JpaRepository<PharmacyLoyaltyConfig, UUID> {

    Optional<PharmacyLoyaltyConfig> findByTenantId(UUID tenantId);
}
