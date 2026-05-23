package io.conddo.core.tenant;

import java.util.Optional;
import java.util.UUID;

/**
 * Holds the current request's tenant id on a thread-local. Populated by
 * {@link TenantFilter} at the edge and read by {@link TenantSession} when a
 * transaction begins, so PostgreSQL Row Level Security can scope every query.
 *
 * <p>In production the tenant is resolved from the subdomain
 * ({@code businessname.conddo.io}) and/or the JWT claim (PRD §6.2, §6.3); in
 * Phase 0 it comes from the {@code X-Tenant-Id} header.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static Optional<UUID> get() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static UUID require() {
        UUID tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new TenantContextMissingException();
        }
        return tenantId;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
