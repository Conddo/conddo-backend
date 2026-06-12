package io.conddo.studio.platform;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlatformFeatureFlagRepository extends JpaRepository<PlatformFeatureFlag, UUID> {

    Optional<PlatformFeatureFlag> findByTenantIdAndFeatureKey(UUID tenantId, String featureKey);

    List<PlatformFeatureFlag> findAllByOrderByInterestAtDesc();

    List<PlatformFeatureFlag> findByFeatureKeyOrderByInterestAtDesc(String featureKey);
}
