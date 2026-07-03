package io.conddo.core.repository;

import io.conddo.core.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

/**
 * RLS scopes every query to the current tenant. The low-stock query also feeds
 * the dashboard KPI (§11.1): a positive reorder threshold reached by stock.
 */
public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    @Query("select p from Product p where p.reorderThreshold > 0 and p.stock <= p.reorderThreshold")
    List<Product> findLowStock();

    @Query("select count(p) from Product p where p.reorderThreshold > 0 and p.stock <= p.reorderThreshold")
    long countLowStock();

    /** Public catalog detail lookup by per-tenant slug. RLS-scoped to the bound tenant. */
    java.util.Optional<Product> findBySlugAndActiveTrue(String slug);

    /** How many products are tagged to a category. RLS-scoped. */
    int countByCategoryId(UUID categoryId);

    /**
     * Bulk-import lookup. Returns a list rather than Optional because
     * SKU isn't guaranteed unique per tenant — the upload reports
     * duplicates as resolvable errors instead of failing the whole file.
     */
    List<Product> findBySku(String sku);

    /**
     * Restock-candidate snapshot per product — id, name, current stock,
     * reorder threshold, total sold in the window. Used by
     * {@code RestockSuggestionService} to build the LLM prompt.
     *
     * <p>Only pulls active products with a positive reorder threshold —
     * items the tenant actively watches. Sorted by "closest to running
     * out first" so the LLM's context window fills with the most urgent
     * candidates when the tenant has many SKUs.
     */
    @Query(value = """
            SELECT p.id                                          AS id,
                   p.name                                        AS name,
                   p.stock                                       AS stock,
                   p.reorder_threshold                           AS reorder_threshold,
                   COALESCE(SUM(ABS(sm.quantity_change)), 0)     AS sold
              FROM products p
              LEFT JOIN stock_movements sm
                     ON sm.product_id = p.id
                    AND sm.movement_type IN ('SALE_ONLINE', 'SALE_POS')
                    AND sm.created_at >= :since
             WHERE p.reorder_threshold > 0
               AND p.active = TRUE
             GROUP BY p.id, p.name, p.stock, p.reorder_threshold
             ORDER BY (p.stock::float / NULLIF(p.reorder_threshold, 0)) ASC
             LIMIT 40
            """, nativeQuery = true)
    List<RestockCandidate> findRestockCandidates(@org.springframework.data.repository.query.Param("since") java.time.OffsetDateTime since);

    /** Row projection for {@link #findRestockCandidates}. */
    interface RestockCandidate {
        UUID getId();
        String getName();
        int getStock();
        int getReorderThreshold();
        long getSold();
    }
}
