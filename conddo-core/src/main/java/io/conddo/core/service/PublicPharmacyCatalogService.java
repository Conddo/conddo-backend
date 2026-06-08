package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Product;
import io.conddo.core.domain.ProductCategory;
import io.conddo.core.repository.ProductCategoryRepository;
import io.conddo.core.repository.ProductRepository;
import io.conddo.core.tenant.TenantSession;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only public catalog for the merchant's pharmacy website
 * (PHARMACY_PUBLIC_API_SPEC §3). Tenant is bound on TenantContext before
 * each call by the PublicSiteInterceptor; RLS scopes every query.
 *
 * <p>Returns full domain shapes — the controller's DTO is the one that
 * trims fields for the public response (sensitive merchant-side data
 * never leaks).
 */
@Service
public class PublicPharmacyCatalogService {

    private static final int MAX_LIMIT = 50;
    private static final int FEATURED_LIMIT = 8;

    private final ProductRepository productRepository;
    private final ProductCategoryRepository categoryRepository;
    private final TenantSession tenantSession;

    public PublicPharmacyCatalogService(ProductRepository productRepository,
                                        ProductCategoryRepository categoryRepository,
                                        TenantSession tenantSession) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.tenantSession = tenantSession;
    }

    @Transactional(readOnly = true)
    public CatalogPage listProducts(String categorySlug, String search, Boolean featured,
                                    Boolean requiresPrescription, int page, int limit) {
        tenantSession.bind();
        int safePage = Math.max(1, page);
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        if (Boolean.TRUE.equals(featured)) {
            safeLimit = FEATURED_LIMIT;
            safePage = 1;
        }

        UUID categoryId = null;
        if (categorySlug != null && !categorySlug.isBlank()) {
            categoryId = categoryRepository.findBySlug(categorySlug.trim().toLowerCase())
                    .map(ProductCategory::getId)
                    .orElseThrow(() -> new NotFoundException("Unknown category: " + categorySlug));
        }

        Specification<Product> spec = filterSpec(categoryId, search, requiresPrescription);
        Page<Product> result = productRepository.findAll(spec,
                PageRequest.of(safePage - 1, safeLimit,
                        Sort.by(Sort.Direction.ASC, "name")));
        return new CatalogPage(result.getContent(), safePage, safeLimit,
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public Product getProductBySlug(String slug) {
        tenantSession.bind();
        if (slug == null || slug.isBlank()) {
            throw new NotFoundException("Product not found");
        }
        return productRepository.findBySlugAndActiveTrue(slug.trim().toLowerCase())
                .orElseThrow(() -> new NotFoundException("Product not found"));
    }

    @Transactional(readOnly = true)
    public List<CategoryView> listCategories() {
        tenantSession.bind();
        // Tally product counts per category in code — RLS-scoped scan over the
        // tenant's products is cheap at the catalog sizes a single pharmacy runs.
        Map<UUID, Long> counts = new HashMap<>();
        for (Product p : productRepository.findAll()) {
            if (p.isActive() && p.getCategoryId() != null) {
                counts.merge(p.getCategoryId(), 1L, Long::sum);
            }
        }
        List<CategoryView> out = new ArrayList<>();
        for (ProductCategory c : categoryRepository.findAllByOrderByName()) {
            out.add(new CategoryView(c, counts.getOrDefault(c.getId(), 0L)));
        }
        out.sort(Comparator.comparing(v -> v.category().getName()));
        return out;
    }

    @Transactional(readOnly = true)
    public ProductCategory resolveCategory(UUID categoryId) {
        if (categoryId == null) {
            return null;
        }
        tenantSession.bind();
        return categoryRepository.findById(categoryId).orElse(null);
    }

    private static Specification<Product> filterSpec(UUID categoryId, String search,
                                                     Boolean requiresPrescription) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.isTrue(root.get("active")));
            if (categoryId != null) {
                preds.add(cb.equal(root.get("categoryId"), categoryId));
            }
            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase() + "%";
                preds.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("nameBrand"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("nameGeneric"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), like)));
            }
            if (requiresPrescription != null) {
                preds.add(cb.equal(root.get("requiresPrescription"), requiresPrescription));
            }
            return cb.and(preds.toArray(new Predicate[0]));
        };
    }

    // ----- record shapes -----------------------------------------------------

    public record CatalogPage(List<Product> products, int page, int limit,
                              long total, long totalPages) {
    }

    public record CategoryView(ProductCategory category, long productCount) {
    }
}
