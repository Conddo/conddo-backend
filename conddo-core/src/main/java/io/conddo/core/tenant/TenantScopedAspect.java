package io.conddo.core.tenant;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Enforces {@link TenantScoped}. Runs inside the enclosing transaction (see
 * {@link #ORDER} below) so the {@code SELECT set_config(...)} lands on the
 * same connection as the method's queries — otherwise RLS would see a
 * different session state and the whole thing is theatre.
 *
 * <p><b>Ordering.</b> Spring Boot's default puts transaction advice at
 * {@link Ordered#LOWEST_PRECEDENCE} which makes it the INNERMOST advisor —
 * wrong for our purpose. We override that in {@link TenantAopConfig} by
 * re-declaring {@code @EnableTransactionManagement} with a very-high-
 * precedence order, so the transaction interceptor becomes the OUTERMOST
 * wrapper. Chain then runs: {@code Transactional} opens the tx → this
 * aspect binds → method body → this aspect clears (if cross-tenant) →
 * {@code Transactional} commits.
 *
 * <p>If a caller uses {@code @TenantScoped} without a surrounding
 * {@code @Transactional}, the guard throws {@link IllegalStateException}
 * with the offending method's signature — a silent no-op bind would be
 * the worst possible failure mode (reads that look correct but bypass RLS).
 */
@Aspect
@Component
@Order(TenantScopedAspect.ORDER)
public class TenantScopedAspect {

    /**
     * Must be strictly HIGHER than {@link TenantAopConfig}'s explicit
     * transaction-advice order ({@code HIGHEST_PRECEDENCE + 100}) so this
     * aspect sits INSIDE the transaction wrapper. Any int well above that
     * works; 100 leaves generous headroom below for future aspects that
     * need to run even more outer (e.g. correlation-id, request logging).
     */
    public static final int ORDER = 100;

    private final TenantSession tenantSession;

    public TenantScopedAspect(TenantSession tenantSession) {
        this.tenantSession = tenantSession;
    }

    @Around("@annotation(scoped)")
    public Object bind(ProceedingJoinPoint pjp, TenantScoped scoped) throws Throwable {
        // Sanity: if no transaction is active the SET set_config is scoped to
        // an autocommit statement and immediately reset by the JDBC pool. That
        // silently defeats the aspect, so we surface it early instead of
        // returning wrong-looking data. Only guard callers who genuinely
        // forgot @Transactional — a caller who is already inside a
        // higher-level tx (e.g. an @Transactional controller-side facade)
        // will trip the check as expected.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "@TenantScoped requires an active transaction. Add @Transactional "
                            + "to " + pjp.getSignature().toShortString()
                            + " (or its caller) — otherwise the RLS GUC set here is "
                            + "immediately discarded by the connection pool.");
        }

        if (scoped.crossTenant()) {
            tenantSession.bindCrossTenant();
            // Explicit try/catch instead of plain try/finally — Java's finally
            // silently REPLACES the target's exception if the finally block
            // also throws, which would hide a genuine bug behind a "clear
            // failed" message. We preserve the target exception and attach
            // the clear failure as suppressed. The GUC is transaction-local
            // (SET set_config('app.cross_tenant','','true')) so it auto-resets
            // at commit, but we clear eagerly for defence in depth.
            Throwable primary = null;
            try {
                return pjp.proceed();
            } catch (Throwable t) {
                primary = t;
                throw t;
            } finally {
                try {
                    tenantSession.clearCrossTenant();
                } catch (RuntimeException clearFail) {
                    if (primary != null) {
                        primary.addSuppressed(clearFail);
                    } else {
                        // Target succeeded; the clear failure IS the error.
                        throw clearFail;
                    }
                }
            }
        }

        tenantSession.bind();
        return pjp.proceed();
    }

    // Intentional import for future readers scanning the class — the
    // ordering comment above refers to this configuration.
    @SuppressWarnings("unused")
    private static final Class<?> TX_ANNOTATION_ANCHOR = Transactional.class;
}
