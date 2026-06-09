package io.conddo.core.inventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.domain.StockMovement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pharmacy Spec v2 §12A — Real-time stock events. Publishes to Redis
 * channels {@code tenant:{tenantId}:stock.{event}} so the FE dashboard
 * can subscribe and update its inventory views without polling.
 *
 * <p>{@link StringRedisTemplate} is auto-configured by
 * {@code spring-boot-starter-data-redis} when a connection is present.
 * In environments without Redis (test, local-only dev) the field is
 * {@code null} or the publish call throws — both cases are handled
 * fail-safe so the originating stock write never aborts because of a
 * publish failure.
 */
@Component
public class StockEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(StockEventPublisher.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    public StockEventPublisher(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public StockEventPublisher(ObjectMapper objectMapper) {
        this(null, objectMapper);
    }

    /**
     * Publish a single movement. The event channel is derived from the
     * movement type:
     * <ul>
     *   <li>{@code SALE_*} → {@code stock.deducted}</li>
     *   <li>{@code RESTOCK} / {@code TRANSFER_IN} / {@code RETURN} → {@code stock.restocked}</li>
     *   <li>{@code ADJUSTMENT} / {@code EXPIRY_REMOVAL} → {@code stock.adjusted}</li>
     *   <li>{@code RECONCILIATION} → {@code reconciliation.variance}</li>
     * </ul>
     * Also publishes {@code stock.low} / {@code stock.out} when the
     * post-change quantity crosses those thresholds.
     */
    public void publish(StockMovement movement, int reorderLevel) {
        publishEvent(movement.getTenantId(), eventFor(movement), payload(movement));
        if (movement.getQuantityAfter() <= 0) {
            publishEvent(movement.getTenantId(), "stock.out", payload(movement));
        } else if (reorderLevel > 0 && movement.getQuantityAfter() <= reorderLevel) {
            publishEvent(movement.getTenantId(), "stock.low",
                    payload(movement, "reorderLevel", reorderLevel));
        }
    }

    private static String eventFor(StockMovement m) {
        return switch (m.getMovementType()) {
            case "SALE_ONLINE", "SALE_POS" -> "stock.deducted";
            case "RESTOCK", "TRANSFER_IN", "RETURN" -> "stock.restocked";
            case "RECONCILIATION" -> "reconciliation.variance";
            default -> "stock.adjusted";
        };
    }

    private Map<String, Object> payload(StockMovement m, Object... extras) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("movementId", m.getId());
        out.put("productId", m.getProductId());
        out.put("movementType", m.getMovementType());
        out.put("quantityChange", m.getQuantityChange());
        out.put("quantityBefore", m.getQuantityBefore());
        out.put("quantityAfter", m.getQuantityAfter());
        out.put("referenceId", m.getReferenceId());
        out.put("referenceKind", m.getReferenceKind());
        out.put("createdAt", m.getCreatedAt());
        for (int i = 0; i + 1 < extras.length; i += 2) {
            out.put(String.valueOf(extras[i]), extras[i + 1]);
        }
        return out;
    }

    private void publishEvent(UUID tenantId, String event, Map<String, Object> payload) {
        if (redis == null) {
            return;
        }
        String channel = "tenant:" + tenantId + ":" + event;
        try {
            String json = objectMapper.writeValueAsString(payload);
            redis.convertAndSend(channel, json);
        } catch (JsonProcessingException | RedisConnectionFailureException ex) {
            log.warn("Stock event publish failed (channel={}, kind={}): {}",
                    channel, event, ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("Stock event publish failed unexpectedly (channel={}, kind={}): {}",
                    channel, event, ex.toString());
        }
    }
}
