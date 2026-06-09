package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One row in a {@link PharmacyReconciliation} session — the
 * (system_qty, counted_qty, variance) triple for a single product
 * (Pharmacy Spec v2 §12A). {@code system_qty} is the stock snapshot at
 * session start; {@code counted_qty} is the pharmacist's physical
 * count; {@code variance} is (counted - system) — applied as a
 * {@code RECONCILIATION} stock movement when the session completes.
 */
@Entity
@Table(name = "pharmacy_reconciliation_items")
public class PharmacyReconciliationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "reconciliation_id", nullable = false)
    private UUID reconciliationId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "system_qty", nullable = false)
    private int systemQty;

    @Column(name = "counted_qty")
    private Integer countedQty;

    private Integer variance;

    @Column(nullable = false)
    private boolean resolved = false;

    protected PharmacyReconciliationItem() {
    }

    public PharmacyReconciliationItem(UUID tenantId, UUID reconciliationId, UUID productId, int systemQty) {
        this.tenantId = tenantId;
        this.reconciliationId = reconciliationId;
        this.productId = productId;
        this.systemQty = systemQty;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getReconciliationId() {
        return reconciliationId;
    }

    public UUID getProductId() {
        return productId;
    }

    public int getSystemQty() {
        return systemQty;
    }

    public Integer getCountedQty() {
        return countedQty;
    }

    public Integer getVariance() {
        return variance;
    }

    public boolean isResolved() {
        return resolved;
    }

    public void recordCount(int counted) {
        this.countedQty = counted;
        this.variance = counted - this.systemQty;
    }

    public void markResolved() {
        this.resolved = true;
    }
}
