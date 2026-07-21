package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line on an {@link Invoice}. Quantity × unit price → line total,
 * with an optional per-line tax rate that lets a single invoice mix
 * VAT-eligible items with exempt ones.
 */
@Entity
@Table(name = "invoice_lines")
public class InvoiceLine {

    @Id
    private UUID id;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(name = "unit_price_kobo", nullable = false)
    private long unitPriceKobo;

    /** Nullable — a null rate means "no tax applied to this line". */
    @Column(name = "tax_rate_percent")
    private BigDecimal taxRatePercent;

    @Column(name = "line_total_kobo", nullable = false)
    private long lineTotalKobo;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected InvoiceLine() {}

    public InvoiceLine(UUID invoiceId, UUID tenantId, String description,
                        BigDecimal quantity, long unitPriceKobo,
                        BigDecimal taxRatePercent, int sortOrder) {
        this.id = UUID.randomUUID();
        this.invoiceId = invoiceId;
        this.tenantId = tenantId;
        this.description = description;
        this.quantity = quantity;
        this.unitPriceKobo = unitPriceKobo;
        this.taxRatePercent = taxRatePercent;
        this.sortOrder = sortOrder;
        recomputeLineTotal();
    }

    /** quantity × unitPrice, rounded to whole kobo. Called on construct
     *  and on any setter mutation to keep the persisted total in sync. */
    public void recomputeLineTotal() {
        BigDecimal total = BigDecimal.valueOf(unitPriceKobo)
                .multiply(quantity)
                .setScale(0, java.math.RoundingMode.HALF_UP);
        this.lineTotalKobo = total.longValueExact();
    }

    /** Contribution to the invoice's total tax bucket. Zero when the
     *  line has no tax rate. Rounded like the line total. */
    public long taxKobo() {
        if (taxRatePercent == null) return 0L;
        return BigDecimal.valueOf(lineTotalKobo)
                .multiply(taxRatePercent)
                .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.HALF_UP)
                .longValueExact();
    }

    // ----- getters + setters ----------------------------------------------

    public UUID getId() { return id; }
    public UUID getInvoiceId() { return invoiceId; }
    public UUID getTenantId() { return tenantId; }
    public String getDescription() { return description; }
    public BigDecimal getQuantity() { return quantity; }
    public long getUnitPriceKobo() { return unitPriceKobo; }
    public BigDecimal getTaxRatePercent() { return taxRatePercent; }
    public long getLineTotalKobo() { return lineTotalKobo; }
    public int getSortOrder() { return sortOrder; }

    public void setDescription(String description) { this.description = description; }
    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
        recomputeLineTotal();
    }
    public void setUnitPriceKobo(long unitPriceKobo) {
        this.unitPriceKobo = unitPriceKobo;
        recomputeLineTotal();
    }
    public void setTaxRatePercent(BigDecimal taxRatePercent) { this.taxRatePercent = taxRatePercent; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
