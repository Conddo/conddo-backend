package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.BookableService;
import io.conddo.core.repository.BookableServiceRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Tenant-side CRUD for the bookable-services catalogue. All reads/writes
 * bind the RLS session first so isolation holds even when the tenant
 * context is set from a stale JWT.
 */
@Service
public class BookableServiceService {

    private final BookableServiceRepository repository;
    private final TenantSession tenantSession;

    public BookableServiceService(BookableServiceRepository repository, TenantSession tenantSession) {
        this.repository = repository;
        this.tenantSession = tenantSession;
    }

    @Transactional(readOnly = true)
    public List<BookableService> list() {
        tenantSession.bind();
        return repository.findAllByOrderBySortOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public BookableService get(UUID id) {
        tenantSession.bind();
        return require(id);
    }

    @Transactional
    public BookableService create(String name, String description, int durationMinutes,
                                   long priceKobo, boolean active, int sortOrder) {
        tenantSession.bind();
        validate(name, durationMinutes);
        BookableService svc = new BookableService(TenantContext.require(), name, durationMinutes);
        svc.setDescription(description);
        svc.setPriceKobo(priceKobo);
        svc.setActive(active);
        svc.setSortOrder(sortOrder);
        return repository.save(svc);
    }

    @Transactional
    public BookableService update(UUID id, String name, String description, Integer durationMinutes,
                                   Long priceKobo, Boolean active, Integer sortOrder) {
        tenantSession.bind();
        BookableService svc = require(id);
        if (name != null) svc.setName(name);
        if (description != null) svc.setDescription(description);
        if (durationMinutes != null) svc.setDurationMinutes(durationMinutes);
        if (priceKobo != null) svc.setPriceKobo(priceKobo);
        if (active != null) svc.setActive(active);
        if (sortOrder != null) svc.setSortOrder(sortOrder);
        validate(svc.getName(), svc.getDurationMinutes());
        return repository.save(svc);
    }

    @Transactional
    public void delete(UUID id) {
        tenantSession.bind();
        repository.delete(require(id));
    }

    private BookableService require(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Service not found: " + id));
    }

    private static void validate(String name, int durationMinutes) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Service name is required.");
        }
        if (durationMinutes <= 0) {
            throw new IllegalArgumentException("Duration must be at least 1 minute.");
        }
        if (durationMinutes > 24 * 60) {
            throw new IllegalArgumentException("Duration can't exceed 24 hours.");
        }
    }
}
