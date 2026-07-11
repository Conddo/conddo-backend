package io.conddo.core.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Wires the {@link RedisMessageListenerContainer} used by
 * {@link RedisDomainEventSubscriber}. Spring Boot auto-configures the
 * connection factory + templates, but the listener container is opt-in —
 * this bean opts in whenever a {@link RedisConnectionFactory} is present so
 * the domain-event bus lights up automatically in production, silently no-ops
 * in tests without Redis, and never double-registers if the app already
 * provides its own listener container.
 */
@Configuration
public class DomainEventBusConfig {

    @Bean
    @ConditionalOnBean(RedisConnectionFactory.class)
    @ConditionalOnMissingBean(RedisMessageListenerContainer.class)
    public RedisMessageListenerContainer domainEventListenerContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        return container;
    }
}
