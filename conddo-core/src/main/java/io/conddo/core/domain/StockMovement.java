package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Append-only stock movement (Pharmacy Spec v2 §12A). Every change to a
 * product's {@code stock} field — online sale, restock, manual
 * adjustment, reconciliation variance — leaves a row here. The
 * {@code quantity_before} / {@code quantity_after} snapshots let the FE
 * render a complete stock timeline without joining back to
 * {@code products}.
 */
@Entity
@Table(name = "pharmacy_stock_movements")
public class StockMovement {

    public enum Type {
        SALE_ONLINE,
        SALE_POS,
        RESTOCK,
        ADJUSTMENT,
        RECONCILIATION,
        RETURN,
        EXPIRY_REMOVAL,
        TRANSFER_OUT,
        TRANSFER_IN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "movement_type", nullable = false, length = 40)
    private String movementType;

    @Column(name = "quantity_change", nullable = false)
    private int quantityChange;

    @Column(name = "quantity_before", nullable = false)
    private int quantityBefore;

    @Column(name = "quantity_after", nullable = false)
    private int quantityAfter;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "reference_kind", length = 40)
    private String referenceKind;

    private String note;

    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected StockMovement() {
    }

    public StockMovement(UUID tenantId, UUID productId, Type type,
                         int quantityChange, int quantityBefore, int quantityAfter,
                         UUID referenceId, String referenceKind,
                         String note, UUID createdBy) {
        this.tenantId = tenantId;
        this.productId = productId;
        this.movementType = type.name();
        this.quantityChange = quantityChange;
        this.quantityBefore = quantityBefore;
        this.quantityAfter = quantityAfter;
        this.referenceId = referenceId;
        this.referenceKind = referenceKind;
        this.note = note;
        this.createdBy = createdBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getMovementType() {
        return movementType;
    }

    public int getQuantityChange() {
        return quantityChange;
    }

    public int getQuantityBefore() {
        return quantityBefore;
    }

    public int getQuantityAfter() {
        return quantityAfter;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public String getReferenceKind() {
        return referenceKind;
    }

    public String getNote() {
        return note;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
