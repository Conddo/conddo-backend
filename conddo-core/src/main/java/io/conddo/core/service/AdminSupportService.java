package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.TenantRequest;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.TenantRequestRepository;
import io.conddo.core.tenant.TenantScoped;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Studio-facing support surface. Lists tenant requests across every
 * tenant, records admin replies, updates status/priority.
 *
 * <p>Every method runs under {@code @TenantScoped(crossTenant = true)}
 * so RLS lets us read from any tenant's requests without a per-tenant
 * bind cycle. The admin's staff id is threaded through so the reply
 * carries an audit trail on {@code responded_by}.
 */
@Service
public class AdminSupportService {

    private static final Set<String> VALID_STATUSES = Set.of(
            TenantRequest.STATUS_OPEN,
            TenantRequest.STATUS_IN_PROGRESS,
            TenantRequest.STATUS_RESOLVED,
            TenantRequest.STATUS_DISMISSED);

    private static final Set<String> VALID_PRIORITIES = Set.of(
            TenantRequest.PRIORITY_LOW,
            TenantRequest.PRIORITY_NORMAL,
            TenantRequest.PRIORITY_HIGH);

    private final TenantRequestRepository requestRepository;
    private final TenantRepository tenantRepository;
    private final Clock clock;

    public AdminSupportService(TenantRequestRepository requestRepository,
                               TenantRepository tenantRepository,
                               Clock clock) {
        this.requestRepository = requestRepository;
        this.tenantRepository = tenantRepository;
        this.clock = clock;
    }

    @TenantScoped(crossTenant = true)
    @Transactional(readOnly = true)
    public List<RequestWithTenant> list(String statusFilter) {
        List<TenantRequest> requests = (statusFilter == null || statusFilter.isBlank() || "ALL".equalsIgnoreCase(statusFilter))
                ? requestRepository.findAllCrossTenant()
                : requestRepository.findByStatusCrossTenant(statusFilter.trim().toUpperCase());
        return decorate(requests);
    }

    @TenantScoped(crossTenant = true)
    @Transactional(readOnly = true)
    public RequestWithTenant one(UUID id) {
        TenantRequest r = requestRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Request not found"));
        Tenant tenant = tenantRepository.findById(r.getTenantId()).orElse(null);
        return new RequestWithTenant(r, tenant);
    }

    @TenantScoped(crossTenant = true)
    @Transactional
    public RequestWithTenant respond(UUID id, String response, UUID staffId) {
        TenantRequest r = requestRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Request not found"));
        if (response == null || response.isBlank()) {
            throw new IllegalArgumentException("response is required");
        }
        r.applyResponse(response.trim(), staffId, OffsetDateTime.now(clock));
        // The admin's reply is meaningful evidence that we've engaged, so
        // roll the request from OPEN → IN_PROGRESS automatically. Explicit
        // status change (via setStatus below) still overrides.
        if (TenantRequest.STATUS_OPEN.equals(r.getStatus())) {
            r.changeStatus(TenantRequest.STATUS_IN_PROGRESS);
        }
        return decorate(requestRepository.save(r));
    }

    @TenantScoped(crossTenant = true)
    @Transactional
    public RequestWithTenant setStatus(UUID id, String status) {
        String normalised = requireValidStatus(status);
        TenantRequest r = requestRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Request not found"));
        r.changeStatus(normalised);
        return decorate(requestRepository.save(r));
    }

    @TenantScoped(crossTenant = true)
    @Transactional
    public RequestWithTenant setPriority(UUID id, String priority) {
        String normalised = requireValidPriority(priority);
        TenantRequest r = requestRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Request not found"));
        r.changePriority(normalised);
        return decorate(requestRepository.save(r));
    }

    @TenantScoped(crossTenant = true)
    @Transactional(readOnly = true)
    public Map<String, Long> statusCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (var s : VALID_STATUSES) counts.put(s, 0L);
        for (var row : requestRepository.countByStatusCrossTenant()) {
            counts.put(row.getStatus(), row.getCount());
        }
        return counts;
    }

    // ----- helpers ---------------------------------------------------------

    private List<RequestWithTenant> decorate(List<TenantRequest> requests) {
        // Batch-load tenants so the list endpoint doesn't do N+1.
        var tenantIds = requests.stream().map(TenantRequest::getTenantId).toList();
        Map<UUID, Tenant> byId = new HashMap<>();
        for (Tenant t : tenantRepository.findAllById(tenantIds)) {
            byId.put(t.getId(), t);
        }
        return requests.stream()
                .map(r -> new RequestWithTenant(r, byId.get(r.getTenantId())))
                .toList();
    }

    private RequestWithTenant decorate(TenantRequest request) {
        Tenant tenant = tenantRepository.findById(request.getTenantId()).orElse(null);
        return new RequestWithTenant(request, tenant);
    }

    private static String requireValidStatus(String status) {
        String upper = status == null ? "" : status.trim().toUpperCase();
        if (!VALID_STATUSES.contains(upper)) {
            throw new IllegalArgumentException(
                    "status must be one of " + VALID_STATUSES + ", got '" + status + "'");
        }
        return upper;
    }

    private static String requireValidPriority(String priority) {
        String upper = priority == null ? "" : priority.trim().toUpperCase();
        if (!VALID_PRIORITIES.contains(upper)) {
            throw new IllegalArgumentException(
                    "priority must be one of " + VALID_PRIORITIES + ", got '" + priority + "'");
        }
        return upper;
    }

    // ----- wire shapes -----------------------------------------------------

    public record RequestWithTenant(TenantRequest request, Tenant tenant) {}
}
