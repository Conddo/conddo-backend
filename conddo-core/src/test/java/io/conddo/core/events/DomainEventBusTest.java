package io.conddo.core.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Behavioural contract for {@link DomainEventBus}. Covers the three claims
 * the class javadoc makes:
 * <ol>
 *   <li>Local Spring publisher always fires (fan-out to in-JVM listeners
 *       is the non-negotiable path).</li>
 *   <li>Redis relay fires when the template is present, on the shared
 *       {@link DomainEventBus#CHANNEL}, with a wire envelope carrying the
 *       event's FQN + JSON body.</li>
 *   <li>Redis failure never bubbles up — a listener's local firing must not
 *       roll back on a cross-pod-relay hiccup.</li>
 *   <li>{@code publishLocalOnly} — the receive-side path called by
 *       {@link RedisDomainEventSubscriber} — fires locally but never
 *       re-enters the relay, so a two-pod cluster does not ping-pong.</li>
 * </ol>
 */
class DomainEventBusTest {

    private final ApplicationEventPublisher local = mock(ApplicationEventPublisher.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publishFansOutLocallyAndRelaysOnRedis() throws Exception {
        DomainEventBus bus = new DomainEventBus(local, redis, mapper);
        SampleEvent event = new SampleEvent(UUID.randomUUID(), "hello");

        bus.publish(event);

        verify(local).publishEvent(event);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq(DomainEventBus.CHANNEL), body.capture());
        DomainEventBus.RedisEnvelope env = mapper.readValue(body.getValue(),
                DomainEventBus.RedisEnvelope.class);
        assertEquals(SampleEvent.class.getName(), env.type());
        SampleEvent decoded = mapper.readValue(env.payload(), SampleEvent.class);
        assertEquals(event, decoded);
    }

    @Test
    void publishWithoutRedisStillFiresLocally() {
        DomainEventBus bus = new DomainEventBus(local, null, mapper);
        SampleEvent event = new SampleEvent(UUID.randomUUID(), "no-redis");

        bus.publish(event);

        verify(local).publishEvent(event);
        // No Redis to talk to; the mock (unwired) receives nothing.
        verifyNoInteractions(redis);
    }

    @Test
    void publishSwallowsRedisConnectionFailure() {
        DomainEventBus bus = new DomainEventBus(local, redis, mapper);
        doThrow(new RedisConnectionFailureException("boom"))
                .when(redis).convertAndSend(eq(DomainEventBus.CHANNEL), any(String.class));
        SampleEvent event = new SampleEvent(UUID.randomUUID(), "redis-down");

        // Must not throw — the in-JVM path already fired; the relay is best-effort.
        assertDoesNotThrow(() -> bus.publish(event));
        verify(local).publishEvent(event);
    }

    @Test
    void publishSwallowsUnexpectedRuntime() {
        DomainEventBus bus = new DomainEventBus(local, redis, mapper);
        doThrow(new RuntimeException("kaboom"))
                .when(redis).convertAndSend(eq(DomainEventBus.CHANNEL), any(String.class));
        SampleEvent event = new SampleEvent(UUID.randomUUID(), "wat");

        assertDoesNotThrow(() -> bus.publish(event));
        verify(local).publishEvent(event);
    }

    @Test
    void publishSwallowsSerializationFailure() throws JsonProcessingException {
        // A mock ObjectMapper that fails on writeValueAsString(RedisEnvelope)
        // simulates a serialisation problem — must not roll back the local fire.
        ObjectMapper broken = mock(ObjectMapper.class);
        doThrow(new JsonProcessingException("cant serialise") {})
                .when(broken).writeValueAsString(any(Object.class));
        DomainEventBus bus = new DomainEventBus(local, redis, broken);
        SampleEvent event = new SampleEvent(UUID.randomUUID(), "unserialisable");

        assertDoesNotThrow(() -> bus.publish(event));
        verify(local).publishEvent(event);
        verifyNoInteractions(redis);
    }

    /** The critical loop-prevention path — the Redis subscriber calls this
     *  when it receives an event from a sibling pod. If the bus re-emitted
     *  onto Redis here, two pods would ping-pong forever. */
    @Test
    void publishLocalOnlyDoesNotTouchRedis() {
        DomainEventBus bus = new DomainEventBus(local, redis, mapper);
        SampleEvent event = new SampleEvent(UUID.randomUUID(), "from-sibling-pod");

        bus.publishLocalOnly(event);

        verify(local).publishEvent(event);
        verify(redis, never()).convertAndSend(any(String.class), any(String.class));
    }

    @Test
    void publishNullEventIsANoop() {
        DomainEventBus bus = new DomainEventBus(local, redis, mapper);

        bus.publish(null);
        bus.publishLocalOnly(null);

        verifyNoInteractions(local);
        verifyNoInteractions(redis);
    }

    /** Minimal DomainEvent — record fields serialise cleanly by default. */
    record SampleEvent(UUID tenantId, String note) implements DomainEvent {}
}
