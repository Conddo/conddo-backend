package io.conddo.core.repository;

import io.conddo.core.domain.TenantFeatureFlag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantFeatureFlagRepository extends JpaRepository<TenantFeatureFlag, UUID> {

    List<TenantFeatureFlag> findByTenantId(UUID tenantId);

    Optional<TenantFeatureFlag> findByTenantIdAndFeatureKey(UUID tenantId, String featureKey);
}
