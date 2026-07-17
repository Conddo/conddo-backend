package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.TenantRequest;
import io.conddo.core.repository.TenantRequestRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantScoped;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant-facing support surface. Tenants submit new requests and read
 * their own history. Cannot mutate status, priority, or admin_response —
 * those flow through {@link io.conddo.core.service.AdminSupportService}
 * from the studio side.
 */
@Service
public class SupportService {

    /** Whitelist so a client can't invent new kinds — the DB CHECK also
     *  enforces this, but rejecting here gives a friendlier 400 instead
     *  of a 500 that leaks the DB error to the FE. */
    private static final Set<String> VALID_KINDS = Set.of(
            TenantRequest.KIND_FEATURE,
            TenantRequest.KIND_COMPLAINT,
            TenantRequest.KIND_BUG,
            TenantRequest.KIND_QUESTION);

    private static final int TITLE_MAX = 200;
    private static final int BODY_MAX = 5_000;

    private final TenantRequestRepository repository;

    public SupportService(TenantRequestRepository repository) {
        this.repository = repository;
    }

    @TenantScoped
    @Transactional
    public TenantRequest submit(UUID userId, String kind, String title, String body) {
        String normalisedKind = normaliseKind(kind);
        String cleanTitle = requireNonBlank(title, "title", TITLE_MAX);
        String cleanBody = requireNonBlank(body, "body", BODY_MAX);
        TenantRequest request = new TenantRequest(
                TenantContext.require(), userId, normalisedKind, cleanTitle, cleanBody);
        return repository.save(request);
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public List<TenantRequest> mine() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public TenantRequest one(UUID id) {
        TenantRequest r = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Request not found"));
        // findById respects RLS — a foreign tenant's row returns empty. If we
        // got a row, it belongs to the caller's tenant. Return as-is.
        return r;
    }

    // ----- helpers ---------------------------------------------------------

    private static String normaliseKind(String kind) {
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        String upper = kind.trim().toUpperCase();
        if (!VALID_KINDS.contains(upper)) {
            throw new IllegalArgumentException(
                    "kind must be one of " + VALID_KINDS + ", got '" + kind + "'");
        }
        return upper;
    }

    private static String requireNonBlank(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }
}
