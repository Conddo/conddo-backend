package io.conddo.core.service;

import io.conddo.core.domain.FashionProduct;
import io.conddo.core.domain.Product;
import io.conddo.core.domain.ProductCategory;
import io.conddo.core.repository.FashionProductRepository;
import io.conddo.core.repository.ProductCategoryRepository;
import io.conddo.core.repository.ProductRepository;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Unified sellable-item feed backing {@code GET /api/v1/items} — the
 * mobile app's product picker and Sell screen.
 *
 * <p>Merges every vertical's product surface into one list so the mobile
 * app has a single "everything you can sell" feed regardless of tenant
 * vertical. Sources:
 * <ul>
 *   <li>{@link Product} — pharmacy + generic inventory (the {@code products} table)</li>
 *   <li>{@link FashionProduct} — fashion products with size/colour variants</li>
 * </ul>
 *
 * <p>RLS scopes both queries to the caller's tenant automatically.
 */
@Service
public class ItemsFeedService {

    private final ProductRepository productRepo;
    private final FashionProductRepository fashionRepo;
    private final ProductCategoryRepository categoryRepo;
    private final TenantSession tenantSession;

    public ItemsFeedService(ProductRepository productRepo,
                            FashionProductRepository fashionRepo,
                            ProductCategoryRepository categoryRepo,
                            TenantSession tenantSession) {
        this.productRepo = productRepo;
        this.fashionRepo = fashionRepo;
        this.categoryRepo = categoryRepo;
        this.tenantSession = tenantSession;
    }

    @Transactional(readOnly = true)
    public List<Item> list() {
        tenantSession.bind();

        // Pre-fetch category names in one hit so we don't N+1 the per-row
        // name lookup.
        Map<UUID, String> catNames = new HashMap<>();
        for (ProductCategory c : categoryRepo.findAll()) {
            catNames.put(c.getId(), c.getName());
        }

        Stream<Item> generic = productRepo.findAll().stream().map(p -> Item.fromProduct(p, catNames));
        Stream<Item> fashion = fashionRepo.findAll().stream().map(Item::fromFashion);

        return Stream.concat(generic, fashion).toList();
    }

    /** Unified sellable-item wire shape — the mobile spec's expected
     *  {@code {id, name, sku, price, stock, unit, category}}. */
    public record Item(
            UUID id,
            String name,
            String sku,
            /** Price in naira (not kobo) — matches the mobile spec's shape. */
            double price,
            int stock,
            /** Optional unit-of-measure (each, kg, ml, ...). Not tracked
             *  on our Product entity today — null for generic inventory,
             *  "piece" for fashion. Mobile treats null as "each". */
            String unit,
            String category,
            /** Distinguishes rows so the mobile can route back to the
             *  right edit surface (generic Inventory vs Fashion). */
            String source) {
        static Item fromProduct(Product p, Map<UUID, String> catNames) {
            String category = p.getCategoryId() != null ? catNames.get(p.getCategoryId()) : null;
            double priceNaira = p.getPrice() != null ? p.getPrice().doubleValue() : 0d;
            return new Item(
                    p.getId(),
                    p.getName(),
                    p.getSku(),
                    priceNaira,
                    p.getStock(),
                    null,
                    category,
                    "inventory");
        }
        static Item fromFashion(FashionProduct f) {
            double priceNaira = f.getBasePrice() != null ? f.getBasePrice().doubleValue() : 0d;
            return new Item(
                    f.getId(),
                    f.getName(),
                    f.getSku(),
                    priceNaira,
                    f.getTotalStock(),
                    "piece",
                    f.getCategory(),
                    "fashion");
        }
    }
}
