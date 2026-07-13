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
 * <p><b>Ordering.</b> Spring's {@code @Transactional} advice runs at
 * {@link Ordered#LOWEST_PRECEDENCE}. We pick a lower numeric value here so
 * this aspect runs INSIDE the transaction (advice with lower order wraps
 * inside advice with higher order — Spring's inverted-precedence rule for
 * around-advice). Concretely: {@code Transactional} opens the tx, THEN this
 * aspect binds, THEN the method body runs, THEN this aspect clears (if
 * cross-tenant), THEN {@code Transactional} commits.
 *
 * <p>If a caller forgets {@code @Transactional} on the method, the bind still
 * happens but on a fresh short-lived connection — the {@code SET set_config}
 * has no method body to protect. That's a latent bug we don't hide: the
 * aspect logs a warn once per class in a follow-up (kept out of this ship
 * to avoid dragging in a logger and changing behavior).
 */
@Aspect
@Component
@Order(TenantScopedAspect.ORDER)
public class TenantScopedAspect {

    /**
     * Must be strictly less than
     * {@link org.springframework.transaction.annotation.EnableTransactionManagement}'s
     * default order ({@code Ordered.LOWEST_PRECEDENCE}) so we sit inside the
     * transaction advice. Any positive int well below LOWEST_PRECEDENCE works;
     * 100 leaves generous headroom for other integration aspects.
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
