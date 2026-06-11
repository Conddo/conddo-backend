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
}
