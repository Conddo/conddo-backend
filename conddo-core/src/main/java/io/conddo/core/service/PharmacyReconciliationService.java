package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.PharmacyReconciliation;
import io.conddo.core.domain.PharmacyReconciliationItem;
import io.conddo.core.domain.Product;
import io.conddo.core.domain.StockMovement;
import io.conddo.core.repository.PharmacyReconciliationItemRepository;
import io.conddo.core.repository.PharmacyReconciliationRepository;
import io.conddo.core.repository.ProductRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reconciliation lifecycle (Pharmacy Spec v2 §12A). A session opens
 * with a snapshot of every active product's stock; the pharmacist fills
 * in physical counts; on {@code complete} we apply each variance as a
 * {@code RECONCILIATION} stock movement so the audit trail is uniform.
 */
@Service
public class PharmacyReconciliationService {

    private final PharmacyReconciliationRepository sessionRepository;
    private final PharmacyReconciliationItemRepository itemRepository;
    private final ProductRepository productRepository;
    private final StockMovementService movementService;
    private final TenantSession tenantSession;
    private final Clock clock;

    public PharmacyReconciliationService(PharmacyReconciliationRepository sessionRepository,
                                         PharmacyReconciliationItemRepository itemRepository,
                                         ProductRepository productRepository,
                                         StockMovementService movementService,
                                         TenantSession tenantSession,
                                         Clock clock) {
        this.sessionRepository = sessionRepository;
        this.itemRepository = itemRepository;
        this.productRepository = productRepository;
        this.movementService = movementService;
        this.tenantSession = tenantSession;
        this.clock = clock;
    }

    @Transactional
    public Started start(UUID startedBy, String notes) {
        tenantSession.bind();
        PharmacyReconciliation session = sessionRepository.save(
                new PharmacyReconciliation(TenantContext.require(), startedBy, notes));
        List<Product> products = productRepository.findAll();
        List<PharmacyReconciliationItem> items = new ArrayList<>();
        for (Product p : products) {
            if (!p.isActive()) {
                continue;
            }
            items.add(itemRepository.save(new PharmacyReconciliationItem(
                    TenantContext.require(), session.getId(), p.getId(), p.getStock())));
        }
        return new Started(session, items.size());
    }

    @Transactional(readOnly = true)
    public Loaded get(UUID id) {
        tenantSession.bind();
        PharmacyReconciliation session = sessionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Reconciliation not found"));
        return new Loaded(session, itemRepository.findByReconciliationIdOrderByProductId(id));
    }

    @Transactional
    public Loaded submitCounts(UUID reconciliationId, List<CountInput> counts) {
        tenantSession.bind();
        PharmacyReconciliation session = sessionRepository.findById(reconciliationId)
                .orElseThrow(() -> new NotFoundException("Reconciliation not found"));
        if (!PharmacyReconciliation.IN_PROGRESS.equals(session.getStatus())) {
            throw new IllegalArgumentException("Reconciliation is not in progress");
        }
        for (CountInput in : counts) {
            Optional<PharmacyReconciliationItem> existing = itemRepository
                    .findByReconciliationIdAndProductId(reconciliationId, in.productId());
            PharmacyReconciliationItem item = existing.orElseGet(() -> {
                Product p = productRepository.findById(in.productId())
                        .orElseThrow(() -> new NotFoundException(
                                "Product not in reconciliation: " + in.productId()));
                return itemRepository.save(new PharmacyReconciliationItem(
                        TenantContext.require(), reconciliationId, p.getId(), p.getStock()));
            });
            item.recordCount(in.countedQty());
            itemRepository.save(item);
        }
        return get(reconciliationId);
    }

    @Transactional
    public Summary complete(UUID reconciliationId, UUID completedBy) {
        tenantSession.bind();
        PharmacyReconciliation session = sessionRepository.findById(reconciliationId)
                .orElseThrow(() -> new NotFoundException("Reconciliation not found"));
        if (!PharmacyReconciliation.IN_PROGRESS.equals(session.getStatus())) {
            throw new IllegalArgumentException("Reconciliation is not in progress");
        }
        List<PharmacyReconciliationItem> items =
                itemRepository.findByReconciliationIdOrderByProductId(reconciliationId);
        int matched = 0;
        int variance = 0;
        int adjustmentsApplied = 0;
        int totalVarianceUnits = 0;
        for (PharmacyReconciliationItem item : items) {
            if (item.getCountedQty() == null) {
                continue;
            }
            int delta = item.getVariance() == null ? 0 : item.getVariance();
            if (delta == 0) {
                matched++;
            } else {
                variance++;
                totalVarianceUnits += delta;
                movementService.recordMovement(item.getProductId(),
                        StockMovement.Type.RECONCILIATION, delta,
                        reconciliationId, "RECONCILIATION",
                        "Reconciliation variance",
                        completedBy);
                adjustmentsApplied++;
            }
            item.markResolved();
            itemRepository.save(item);
        }
        session.markCompleted(completedBy, OffsetDateTime.now(clock));
        sessionRepository.save(session);
        return new Summary(items.size(), matched, variance, totalVarianceUnits, adjustmentsApplied);
    }

    // ----- DTOs --------------------------------------------------------------

    public record Started(PharmacyReconciliation session, int totalProducts) {
    }

    public record Loaded(PharmacyReconciliation session, List<PharmacyReconciliationItem> items) {
    }

    public record CountInput(UUID productId, int countedQty) {
    }

    public record Summary(int totalProducts, int matched, int variance,
                          int totalVarianceUnits, int adjustmentsApplied) {
    }
}
