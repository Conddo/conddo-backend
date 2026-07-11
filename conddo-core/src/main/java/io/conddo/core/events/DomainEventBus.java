package io.conddo.core.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Cross-pod publish surface for {@link DomainEvent}. Replaces direct calls to
 * Spring's {@link ApplicationEventPublisher} in service code — those only
 * reach the JVM that published, which blocks horizontal scale-out. This bus
 * fans out to both:
 * <ul>
 *   <li><b>Local Spring event publisher</b> — so in-JVM listeners keep
 *       working with no signature change ({@code @EventListener} on the
 *       event type). Same guarantees as before, including
 *       {@code @TransactionalEventListener} semantics.</li>
 *   <li><b>Redis pub/sub</b> — a fire-and-forget broadcast to every other
 *       API pod, where {@link RedisDomainEventSubscriber} republishes the
 *       event as a local Spring event. Listeners on those pods see it too.</li>
 * </ul>
 *
 * <p><b>Loop prevention.</b> The Redis subscriber sets a marker on the
 * republished event via {@link RedisDomainEventSubscriber} and this bus
 * refuses to re-emit already-relayed events — otherwise a two-pod cluster
 * would ping-pong forever.
 *
 * <p><b>Missing Redis.</b> {@link StringRedisTemplate} is optional. Without
 * it, {@code publish} still fans out locally so single-pod deployments
 * (dev, tests) work unchanged. A production pod with Redis unreachable logs
 * a warn but never throws — an in-flight tx must not roll back because the
 * event relay failed.
 */
@Component
public class DomainEventBus {

    private static final Logger log = LoggerFactory.getLogger(DomainEventBus.class);
    /** Single Redis channel for all domain events; the subscriber filters by type. */
    static final String CHANNEL = "conddo.domain.events";

    private final ApplicationEventPublisher local;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    public DomainEventBus(ApplicationEventPublisher local,
                          StringRedisTemplate redis,
                          ObjectMapper objectMapper) {
        this.local = local;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** Fires the event locally AND on Redis (if present). Never throws. */
    public void publish(DomainEvent event) {
        if (event == null) return;
        local.publishEvent(event);
        relay(event);
    }

    /** Called by {@link RedisDomainEventSubscriber} when it receives an event
     *  from Redis — publishes locally without re-relaying (loop prevention). */
    void publishLocalOnly(DomainEvent event) {
        if (event == null) return;
        local.publishEvent(event);
    }

    private void relay(DomainEvent event) {
        if (redis == null) return;
        try {
            RedisEnvelope envelope = new RedisEnvelope(
                    event.getClass().getName(),
                    objectMapper.writeValueAsString(event));
            redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(envelope));
        } catch (JsonProcessingException | RedisConnectionFailureException ex) {
            // In-JVM listeners already ran; the cross-pod fan-out failing is
            // best-effort. Loud enough to alert, quiet enough to not roll a tx.
            log.warn("Redis relay failed for {}: {}",
                    event.getClass().getSimpleName(), ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("Redis relay failed unexpectedly for {}: {}",
                    event.getClass().getSimpleName(), ex.toString());
        }
    }

    /** Wire format on the Redis channel — the FQN lets the subscriber pick
     *  the concrete record class to deserialise into. Kept package-private
     *  because only {@link RedisDomainEventSubscriber} needs to read it. */
    record RedisEnvelope(String type, String payload) {}
}
