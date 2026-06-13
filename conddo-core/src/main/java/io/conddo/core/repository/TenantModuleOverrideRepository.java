package io.conddo.core.repository;

import io.conddo.core.domain.TenantModuleOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantModuleOverrideRepository extends JpaRepository<TenantModuleOverride, UUID> {

    /** All overrides for the bound tenant (RLS-scoped). */
    List<TenantModuleOverride> findAll();

    Optional<TenantModuleOverride> findByModuleId(String moduleId);
}
