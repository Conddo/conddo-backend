package io.conddo.core.tenant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative RLS binding for a service method. When present,
 * {@link TenantScopedAspect} calls {@link TenantSession#bind()} (or
 * {@link TenantSession#bindCrossTenant()} when {@link #crossTenant()} is true)
 * immediately after entry — inside the caller's transaction — so the
 * PostgreSQL RLS policies see {@code app.tenant_id} (or {@code app.cross_tenant})
 * for every statement the method issues.
 *
 * <p>Why an annotation rather than a manual call:
 * <ul>
 *   <li><b>Correctness bomb defused.</b> Forgetting the manual bind on a new
 *       service method silently runs the query with no tenant GUC set, which
 *       either returns empty rows or — if a prior request left
 *       {@code app.cross_tenant=true} on the pooled connection — reads across
 *       tenants. The aspect makes the bind unforgettable.</li>
 *   <li><b>Explicit intent at the signature.</b> The annotation is visible in
 *       code review. A reviewer instantly sees "this is tenant-scoped" or
 *       "this deliberately reads cross-tenant" without opening the body.</li>
 *   <li><b>Backwards-compatible.</b> Introducing this does not require touching
 *       the 286 existing {@code tenantSession.bind()} call sites — a redundant
 *       bind is a no-op. Services can migrate one method at a time.</li>
 * </ul>
 *
 * <p>Placement:
 * <ul>
 *   <li>Method-level only (repositories are not the boundary; the service
 *       method is). Class-level would over-apply to pre-transaction utility
 *       methods.</li>
 *   <li>Must be on a Spring-proxied bean method (i.e. the service is a
 *       {@code @Service} / {@code @Component}); self-invocation within the
 *       same bean bypasses the proxy and therefore the aspect. Same rule as
 *       {@code @Transactional}, and for the same reason.</li>
 *   <li>Place it OUTSIDE (before) {@code @Transactional} on the method — the
 *       aspect runs after Spring's transaction advice starts the transaction,
 *       so the {@code SET set_config(...)} lands on the right connection.
 *       Ordering is set explicitly in {@link TenantScopedAspect} so this
 *       works regardless of the declaration order.</li>
 * </ul>
 *
 * <p>Cross-tenant use:
 * <pre>{@code
 * @TenantScoped(crossTenant = true)
 * @Transactional(readOnly = true)
 * public Overview snapshot() { ... }
 * }</pre>
 * The aspect calls {@link TenantSession#bindCrossTenant()} on entry and
 * {@link TenantSession#clearCrossTenant()} on exit (finally block). This
 * closes the D9 footgun from the architecture audit — a service that binds
 * cross-tenant and forgets to clear can no longer leak the GUC to the next
 * caller on the pooled connection.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TenantScoped {

    /**
     * When true, the aspect binds the cross-tenant carve-out
     * ({@code app.cross_tenant=true}) instead of a tenant id, and CLEARS it in
     * a finally block on exit. Use for SUPER_ADMIN reads that span tenants
     * (Studio dashboard, cross-tenant analytics). The class-level
     * {@code @PreAuthorize} is still your gate — the aspect only manages the
     * GUC lifecycle.
     */
    boolean crossTenant() default false;
}
