package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Product;
import io.conddo.core.domain.StockMovement;
import io.conddo.core.domain.StockMovement.Type;
import io.conddo.core.inventory.StockEventPublisher;
import io.conddo.core.repository.ProductRepository;
import io.conddo.core.repository.StockMovementRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Central recorder for stock changes (Pharmacy Spec v2 §12A). Every
 * online sale, manual adjustment, restock and reconciliation runs
 * through {@link #recordMovement} so the audit trail is single-source
 * and the Redis event stream stays in lock-step with the product row.
 *
 * <p>Callers MUST already be in a transaction — the method updates
 * {@link Product#getStock()} and inserts the movement in one shot, and
 * the Redis publish happens after the DB write succeeds (it can fail
 * fail-safe without rolling back the stock change).
 */
@Service
public class StockMovementService {

    private final ProductRepository productRepository;
    private final StockMovementRepository movementRepository;
    private final StockEventPublisher events;
    private final TenantSession tenantSession;

    public StockMovementService(ProductRepository productRepository,
                                StockMovementRepository movementRepository,
                                StockEventPublisher events,
                                TenantSession tenantSession) {
        this.productRepository = productRepository;
        this.movementRepository = movementRepository;
        this.events = events;
        this.tenantSession = tenantSession;
    }

    /**
     * Apply a signed delta to a product's stock and persist the
     * movement row. Returns the persisted movement so callers can echo
     * it back to the FE.
     */
    @Transactional
    public StockMovement recordMovement(UUID productId, Type type, int delta,
                                        UUID referenceId, String referenceKind,
                                        String note, UUID createdBy) {
        tenantSession.bind();
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product not found: " + productId));
        int before = product.getStock();
        int after = before + delta;
        if (after < 0) {
            throw new IllegalArgumentException(
                    "Stock would go negative for product " + productId
                            + " (" + before + " " + delta + ")");
        }
        product.adjustStock(delta);
        productRepository.save(product);
        StockMovement saved = movementRepository.save(new StockMovement(
                TenantContext.require(), productId, type, delta, before, after,
                referenceId, referenceKind, note, createdBy));
        events.publish(saved, product.getReorderThreshold());
        return saved;
    }

    /**
     * Set a product's stock to an absolute value (the
     * {@code POST /inventory/adjustment} flow). Computes the delta
     * internally and records an {@code ADJUSTMENT} movement.
     */
    @Transactional
    public StockMovement setAbsolute(UUID productId, int adjustedQty, String reason,
                                     String note, UUID createdBy) {
        tenantSession.bind();
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product not found: " + productId));
        int delta = adjustedQty - product.getStock();
        if (delta == 0) {
            // Still record a zero-delta row so the count appears in audit history.
            int before = product.getStock();
            StockMovement saved = movementRepository.save(new StockMovement(
                    TenantContext.require(), productId, Type.ADJUSTMENT,
                    0, before, before, null, "ADJUSTMENT",
                    composeNote(reason, note), createdBy));
            return saved;
        }
        return recordMovement(productId, Type.ADJUSTMENT, delta,
                null, "ADJUSTMENT", composeNote(reason, note), createdBy);
    }

    /**
     * Bulk restock — one row per item, each becomes its own movement.
     * Returns the list of persisted movements in input order.
     */
    @Transactional
    public List<StockMovement> restock(List<RestockLine> items, String note, UUID createdBy) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("restock: items is required");
        }
        UUID restockBatch = UUID.randomUUID();
        List<StockMovement> out = new ArrayList<>();
        for (RestockLine line : items) {
            if (line.quantity() <= 0) {
                throw new IllegalArgumentException(
                        "restock: quantity must be > 0 for product " + line.productId());
            }
            out.add(recordMovement(line.productId(), Type.RESTOCK, line.quantity(),
                    restockBatch, "RESTOCK", note, createdBy));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Page<StockMovement> list(UUID productId, String movementType,
                                    OffsetDateTime from, OffsetDateTime to,
                                    Pageable pageable) {
        tenantSession.bind();
        Specification<StockMovement> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (productId != null) {
                predicates.add(cb.equal(root.get("productId"), productId));
            }
            if (movementType != null && !movementType.isBlank()) {
                predicates.add(cb.equal(root.get("movementType"), movementType));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            return predicates.isEmpty() ? cb.conjunction()
                    : cb.and(predicates.toArray(new Predicate[0]));
        };
        Pageable sortedByDesc = pageable.getSort().isUnsorted()
                ? org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(),
                        pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "createdAt"))
                : pageable;
        return movementRepository.findAll(spec, sortedByDesc);
    }

    private static String composeNote(String reason, String note) {
        if (reason == null && note == null) {
            return null;
        }
        if (reason == null) {
            return note;
        }
        if (note == null) {
            return reason;
        }
        return reason + " — " + note;
    }

    public record RestockLine(UUID productId, int quantity) {
    }
}
