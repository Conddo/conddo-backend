package io.conddo.core.tenant;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Direct behavioural tests for {@link TenantScopedAspect}. We drive the aspect
 * through its {@code bind()} advice with a hand-rolled {@code ProceedingJoinPoint}
 * mock so the assertions don't depend on Spring proxying + a live tx manager —
 * that route needs an integration test + Testcontainers Postgres and is out of
 * scope for a unit-level check of the aspect's own control flow.
 *
 * <p>What each test proves:
 * <ul>
 *   <li>Tenant-scoped happy path binds via {@link TenantSession#bind()} and
 *       runs the target method. Cross-tenant path does not touch bind().</li>
 *   <li>Cross-tenant happy path binds via
 *       {@link TenantSession#bindCrossTenant()} and clears afterwards, in
 *       the correct order (bind → proceed → clear).</li>
 *   <li>Cross-tenant clear runs even when the target throws — the whole
 *       "close the D9 footgun" claim in the aspect javadoc depends on this
 *       being enforced.</li>
 *   <li>Missing transaction fails fast with a helpful message pointing at
 *       the offending signature, before touching the session at all.</li>
 * </ul>
 */
class TenantScopedAspectTest {

    private final TenantSession session = mock(TenantSession.class);
    private final TenantScopedAspect aspect = new TenantScopedAspect(session);

    @Test
    void bindsTenantAndProceedsWhenAnnotationIsPlain() throws Throwable {
        Object expected = new Object();
        FakeJoinPoint jp = new FakeJoinPoint(() -> expected);
        TenantScoped scoped = plain();

        Object out = insideTransaction(() -> aspect.bind(jp, scoped));

        assertSame(expected, out);
        InOrder order = inOrder(session);
        order.verify(session).bind();
        // Tenant-scoped path must not touch either cross-tenant method.
        order.verifyNoMoreInteractions();
        assertEquals(1, jp.calls);
    }

    @Test
    void bindsCrossTenantAndClearsAfterSuccess() throws Throwable {
        FakeJoinPoint jp = new FakeJoinPoint(() -> "ok");
        TenantScoped scoped = crossTenant();

        Object out = insideTransaction(() -> aspect.bind(jp, scoped));

        assertEquals("ok", out);
        InOrder order = inOrder(session);
        order.verify(session).bindCrossTenant();
        order.verify(session).clearCrossTenant();
        order.verifyNoMoreInteractions();
        assertEquals(1, jp.calls);
    }

    /** The core safety claim: even a thrown exception must not skip the
     *  clear. Otherwise a cross-tenant read that fails leaves
     *  {@code app.cross_tenant=true} on the pooled connection and the next
     *  request on that connection reads across tenants unnoticed. */
    @Test
    void bindsCrossTenantAndClearsEvenWhenTargetThrows() throws Throwable {
        RuntimeException boom = new IllegalStateException("target crashed");
        FakeJoinPoint jp = new FakeJoinPoint(() -> { throw boom; });
        TenantScoped scoped = crossTenant();

        RuntimeException rethrown = assertThrows(IllegalStateException.class,
                () -> insideTransaction(() -> aspect.bind(jp, scoped)));

        assertSame(boom, rethrown);
        InOrder order = inOrder(session);
        order.verify(session).bindCrossTenant();
        order.verify(session).clearCrossTenant();
        order.verifyNoMoreInteractions();
    }

    /** No transaction → no connection to attach the GUC to → the aspect
     *  must fail loud, before touching the session. */
    @Test
    void refusesToBindOutsideTransaction() {
        FakeJoinPoint jp = new FakeJoinPoint(() -> "unreached");
        TenantScoped scoped = plain();

        // Deliberately NOT wrapped in insideTransaction — the static mock
        // isn't installed, so isActualTransactionActive() returns false.
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> aspect.bind(jp, scoped));

        assertNotNull(ex.getMessage());
        // Message must include the target signature so the caller knows
        // which method to fix.
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains(jp.getSignature().toShortString()),
                "error must name the offending method");
        verifyNoInteractions(session);
        assertEquals(0, jp.calls);
    }

    /** When target throws AND clear throws, the target exception must win —
     *  the caller cares about the real failure, not the cleanup noise. The
     *  clear failure rides as a suppressed exception so diagnostics don't
     *  lose it entirely. */
    @Test
    void originalExceptionWinsWhenClearAlsoThrows() throws Throwable {
        RuntimeException target = new IllegalStateException("target crashed");
        RuntimeException clear = new IllegalStateException("clear crashed");
        FakeJoinPoint jp = new FakeJoinPoint(() -> { throw target; });
        TenantScoped scoped = crossTenant();
        doThrow(clear).when(session).clearCrossTenant();

        RuntimeException thrown = assertThrows(IllegalStateException.class,
                () -> insideTransaction(() -> aspect.bind(jp, scoped)));

        assertSame(target, thrown, "target exception must be primary");
        assertEquals(1, thrown.getSuppressed().length, "clear failure must be suppressed");
        assertSame(clear, thrown.getSuppressed()[0]);
    }

    /** When target succeeds but clear throws, the clear failure IS the error —
     *  it's the only observable failure the caller has. */
    @Test
    void clearFailureSurfacesWhenTargetSucceeds() throws Throwable {
        RuntimeException clear = new IllegalStateException("clear crashed");
        FakeJoinPoint jp = new FakeJoinPoint(() -> "ok");
        TenantScoped scoped = crossTenant();
        doThrow(clear).when(session).clearCrossTenant();

        RuntimeException thrown = assertThrows(IllegalStateException.class,
                () -> insideTransaction(() -> aspect.bind(jp, scoped)));

        assertSame(clear, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    // ----- helpers ---------------------------------------------------------

    /** Wraps {@code action} with a static mock of TransactionSynchronizationManager
     *  so the aspect sees an active transaction. */
    private static Object insideTransaction(ThrowingSupplier action) throws Throwable {
        try (var mocked = mockStatic(
                org.springframework.transaction.support.TransactionSynchronizationManager.class)) {
            mocked.when(org.springframework.transaction.support.TransactionSynchronizationManager
                    ::isActualTransactionActive).thenReturn(true);
            return action.get();
        }
    }

    private static TenantScoped plain() {
        return annotation(false);
    }

    private static TenantScoped crossTenant() {
        return annotation(true);
    }

    /** Build a real {@link TenantScoped} instance by reflectively pulling it
     *  off a marker method — safer than hand-mocking an annotation type
     *  (Java has restrictions on annotation reflection equality). */
    private static TenantScoped annotation(boolean crossTenant) {
        try {
            Method m = crossTenant
                    ? Markers.class.getMethod("crossTenant")
                    : Markers.class.getMethod("plain");
            return m.getAnnotation(TenantScoped.class);
        } catch (NoSuchMethodException ex) {
            throw new AssertionError(ex);
        }
    }

    static class Markers {
        @TenantScoped
        public void plain() {}

        @TenantScoped(crossTenant = true)
        public void crossTenant() {}
    }

    @FunctionalInterface
    interface ThrowingSupplier {
        Object get() throws Throwable;
    }

    /** Minimal ProceedingJoinPoint stub. AspectJ's own interface has many
     *  methods, but the aspect uses only {@code proceed()} and
     *  {@code getSignature().toShortString()} — implement those, fail the
     *  rest so a test drift produces a clean AssertionError. */
    static class FakeJoinPoint implements org.aspectj.lang.ProceedingJoinPoint {
        int calls = 0;
        private final ThrowingSupplier body;

        FakeJoinPoint(ThrowingSupplier body) {
            this.body = body;
        }

        @Override
        public Object proceed() throws Throwable {
            calls++;
            return body.get();
        }

        @Override
        public Object proceed(Object[] args) throws Throwable {
            return proceed();
        }

        @Override
        public org.aspectj.lang.Signature getSignature() {
            return new org.aspectj.lang.Signature() {
                @Override public String toShortString() { return "Fake.method()"; }
                @Override public String toLongString() { return toShortString(); }
                @Override public String getName() { return "method"; }
                @Override public int getModifiers() { return 0; }
                @Override public Class<?> getDeclaringType() { return Object.class; }
                @Override public String getDeclaringTypeName() { return "Fake"; }
            };
        }

        // Unused surface — fail loudly if the aspect starts calling anything else.
        @Override public String toShortString() { return getSignature().toShortString(); }
        @Override public String toLongString() { return toShortString(); }
        @Override public Object getThis() { throw new AssertionError("unused"); }
        @Override public Object getTarget() { throw new AssertionError("unused"); }
        @Override public Object[] getArgs() { return new Object[0]; }
        @Override public org.aspectj.lang.reflect.SourceLocation getSourceLocation() { throw new AssertionError("unused"); }
        @Override public String getKind() { throw new AssertionError("unused"); }
        @Override public org.aspectj.lang.JoinPoint.StaticPart getStaticPart() { throw new AssertionError("unused"); }
        @Override public void set$AroundClosure(org.aspectj.runtime.internal.AroundClosure arc) { throw new AssertionError("unused"); }
        @Override public void stack$AroundClosure(org.aspectj.runtime.internal.AroundClosure arc) { throw new AssertionError("unused"); }
    }
}
