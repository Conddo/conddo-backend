package io.conddo.core.events;

import java.util.UUID;

/**
 * Marker for domain events that must fan out across all API pods, not just
 * the JVM that produced them (Architecture P4 — "events are the integration
 * layer"). Publishing via {@link DomainEventBus} broadcasts to every pod so
 * a listener registered on any pod sees the event.
 *
 * <p>Contract:
 * <ul>
 *   <li><b>Tenant-scoped.</b> {@link #tenantId()} identifies the tenant the
 *       event belongs to, so cross-tenant listeners can filter and the Redis
 *       channel name can be namespaced.</li>
 *   <li><b>Small payload — IDs and primitives only.</b> No JPA entities, no
 *       Hibernate proxies, no lazy relations. Listeners load domain state
 *       themselves so they always read post-commit truth.</li>
 *   <li><b>Java-serialisable to JSON via Jackson.</b> {@code record} types
 *       with primitive fields serialise cleanly by default.</li>
 * </ul>
 *
 * <p>Listener rules:
 * <ul>
 *   <li><b>Idempotent, or enqueue a job.</b> Every listener runs on every
 *       pod. If your listener sends an SMS or charges a card, it MUST either
 *       be idempotent (use the event's ids as a de-dup key) or enqueue a job
 *       (BullMQ) and let a single worker do the work exactly once.</li>
 *   <li><b>Non-blocking.</b> Listeners run on the Redis subscriber thread on
 *       receive pods; long work belongs on a job queue.</li>
 *   <li><b>Fail-safe.</b> A listener throwing must not break the bus for
 *       other listeners on the same event; the bus catches and logs.</li>
 * </ul>
 */
public interface DomainEvent {
    UUID tenantId();
}
