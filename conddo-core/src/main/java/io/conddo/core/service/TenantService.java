package io.conddo.core.service;

import io.conddo.core.domain.Tenant;
import io.conddo.core.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Tenant provisioning (signup). Operates on the un-scoped {@code tenants}
 * table, so no tenant context is required here — this is what creates tenants
 * in the first place. (Listing all tenants is a super-admin concern; exposed
 * here only for the Phase 0 demo.)
 */
@Service
public class TenantService {

    private final TenantRepository tenantRepository;

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public Tenant create(String name, String slug, String verticalId, String planId) {
        if (tenantRepository.existsBySlug(slug)) {
            throw new IllegalArgumentException("A tenant with slug '" + slug + "' already exists");
        }
        return tenantRepository.save(new Tenant(name, slug, verticalId, planId));
    }

    @Transactional(readOnly = true)
    public List<Tenant> findAll() {
        return tenantRepository.findAll();
    }
}
