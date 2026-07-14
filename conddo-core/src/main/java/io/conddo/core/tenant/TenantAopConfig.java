package io.conddo.core.tenant;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Overrides Spring Boot's default transaction-advice ordering so that the
 * transaction interceptor becomes the OUTERMOST advisor in the AOP chain,
 * and any custom {@link org.springframework.stereotype.Component} aspect
 * — chiefly {@link TenantScopedAspect} — runs INSIDE the transaction.
 *
 * <p>Without this override, Spring Boot autoconfigures
 * {@code EnableTransactionManagement} at {@link Ordered#LOWEST_PRECEDENCE},
 * which makes the transaction advisor the INNERMOST wrapper. In that
 * ordering, {@link TenantScopedAspect} (whose {@code @Order(100)} is a
 * higher-precedence value) fires BEFORE the transaction opens — and its
 * {@code isActualTransactionActive()} guard throws, breaking every
 * {@code @TenantScoped} call in production. See the aspect javadoc for
 * why the sanity guard is worth keeping despite this.
 *
 * <p>The override sets the transaction interceptor to a very-high-precedence
 * order ({@code HIGHEST_PRECEDENCE + 100}). Leaves headroom above it in
 * case a future security aspect needs to run even more outer, but sits
 * comfortably outside every user-space aspect (which conventionally start
 * at order 100 and grow larger).
 *
 * <p>This is a one-time global change. Nothing else in the codebase reads
 * transaction ordering directly, and the only existing custom aspect is
 * {@code TenantScopedAspect} — audited before landing this.
 */
@Configuration
@EnableTransactionManagement(order = Ordered.HIGHEST_PRECEDENCE + 100)
public class TenantAopConfig {
}
