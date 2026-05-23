package io.conddo.core.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Binds the current {@link TenantContext} to the active database transaction
 * so PostgreSQL RLS policies can scope every statement.
 *
 * <p>It runs {@code SELECT set_config('app.tenant_id', '<uuid>', true)} — the
 * {@code true} makes the setting transaction-local, so it is automatically
 * reset when the transaction ends and never leaks across pooled connections.
 *
 * <p>MUST be called from within an active transaction (e.g. the first line of
 * a {@code @Transactional} service method). Until this is centralised behind an
 * aspect, services call {@link #bind()} explicitly.
 */
@Component
public class TenantSession {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Applies the current tenant to the transaction. Throws if no tenant is in
     * context — tenant-scoped work must never run unscoped.
     */
    public void bind() {
        UUID tenantId = TenantContext.require();
        entityManager
                .createNativeQuery("SELECT set_config('app.tenant_id', :tenantId, true)")
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();
    }
}
