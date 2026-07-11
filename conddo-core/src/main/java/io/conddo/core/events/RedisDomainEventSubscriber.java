package io.conddo.core.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Component;

/**
 * Subscribes to {@link DomainEventBus#CHANNEL} and republishes each received
 * event as a local Spring event so pod-local {@code @EventListener} methods
 * see events that were produced on a sibling pod.
 *
 * <p>Only active when a Redis {@link RedisMessageListenerContainer} bean is
 * available — single-pod / no-Redis setups don't need this at all, and the
 * {@code @ConditionalOnBean} keeps the wiring clean rather than exploding
 * on missing infra.
 */
@Component
@ConditionalOnBean(RedisMessageListenerContainer.class)
public class RedisDomainEventSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisDomainEventSubscriber.class);

    private final DomainEventBus bus;
    private final RedisMessageListenerContainer container;
    private final ObjectMapper objectMapper;

    @Autowired
    public RedisDomainEventSubscriber(DomainEventBus bus,
                                      RedisMessageListenerContainer container,
                                      ObjectMapper objectMapper) {
        this.bus = bus;
        this.container = container;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void subscribe() {
        Topic topic = new ChannelTopic(DomainEventBus.CHANNEL);
        container.addMessageListener(this, topic);
    }

    @Override
    public void onMessage(org.springframework.data.redis.connection.Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody());
            DomainEventBus.RedisEnvelope envelope =
                    objectMapper.readValue(body, DomainEventBus.RedisEnvelope.class);
            Class<?> type = Class.forName(envelope.type());
            if (!DomainEvent.class.isAssignableFrom(type)) {
                log.warn("Redis event skipped, type not a DomainEvent: {}", envelope.type());
                return;
            }
            DomainEvent event = (DomainEvent) objectMapper.readValue(envelope.payload(), type);
            // publishLocalOnly ensures we don't re-emit onto Redis and cause a loop.
            bus.publishLocalOnly(event);
        } catch (ClassNotFoundException ex) {
            // Another pod might be running a newer version with an event class
            // this pod doesn't know about. Ignore rather than crash the listener.
            log.warn("Redis event skipped, unknown class: {}", ex.getMessage());
        } catch (Exception ex) {
            log.warn("Redis event handler failed: {}", ex.toString());
        }
    }
}
