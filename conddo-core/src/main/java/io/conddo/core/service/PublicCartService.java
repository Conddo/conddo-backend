package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.CartItem;
import io.conddo.core.domain.CustomerCart;
import io.conddo.core.domain.Product;
import io.conddo.core.repository.CartItemRepository;
import io.conddo.core.repository.CustomerCartRepository;
import io.conddo.core.repository.ProductRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Customer-side cart (PHARMACY_PUBLIC_API_SPEC §4). Server-side
 * persistence so a customer can pick up where they left off across
 * devices. Add-to-cart REPLACES the quantity on duplicate per spec.
 *
 * <p>Stock validation is intentionally <strong>not</strong> done here
 * — the spec says cart is optimistic, the order endpoint is the source
 * of truth (V25 STOCK_SHORTAGE protects checkout). We only refuse a
 * quantity exceeding stock to give friendlier feedback than waiting for
 * checkout.
 */
@Service
public class PublicCartService {

    private final CustomerCartRepository cartRepository;
    private final CartItemRepository itemRepository;
    private final ProductRepository productRepository;
    private final TenantSession tenantSession;

    public PublicCartService(CustomerCartRepository cartRepository,
                             CartItemRepository itemRepository,
                             ProductRepository productRepository,
                             TenantSession tenantSession) {
        this.cartRepository = cartRepository;
        this.itemRepository = itemRepository;
        this.productRepository = productRepository;
        this.tenantSession = tenantSession;
    }

    @Transactional(readOnly = true)
    public CartSnapshot read(UUID customerId) {
        tenantSession.bind();
        return cartRepository.findByCustomerId(customerId)
                .map(this::snapshot)
                .orElse(CartSnapshot.empty());
    }

    /** Add or replace one product in the cart. Quantity REPLACES per spec. */
    @Transactional
    public CartSnapshot upsertItem(UUID customerId, UUID productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        tenantSession.bind();
        Product product = productRepository.findById(productId)
                .filter(Product::isActive)
                .orElseThrow(() -> new NotFoundException("Product not found"));
        if (product.getStock() < quantity) {
            throw new InsufficientStockException(productId, product.getStock(), quantity);
        }
        CustomerCart cart = cartRepository.findByCustomerId(customerId)
                .orElseGet(() -> cartRepository.save(
                        new CustomerCart(TenantContext.require(), customerId)));

        itemRepository.findByCartIdAndProductId(cart.getId(), productId)
                .ifPresentOrElse(
                        item -> {
                            item.setQuantity(quantity);
                            itemRepository.save(item);
                        },
                        () -> itemRepository.save(new CartItem(
                                TenantContext.require(), cart.getId(), productId, quantity)));
        return snapshot(cart);
    }

    @Transactional
    public CartSnapshot removeItem(UUID customerId, UUID productId) {
        tenantSession.bind();
        CustomerCart cart = cartRepository.findByCustomerId(customerId).orElse(null);
        if (cart == null) {
            return CartSnapshot.empty();
        }
        itemRepository.deleteByCartIdAndProductId(cart.getId(), productId);
        return snapshot(cart);
    }

    @Transactional
    public void clear(UUID customerId) {
        tenantSession.bind();
        cartRepository.findByCustomerId(customerId)
                .ifPresent(cart -> itemRepository.deleteByCartId(cart.getId()));
    }

    /** Used by the checkout flow to read items + product lookup in one shot. */
    @Transactional(readOnly = true)
    public List<CartLine> items(UUID customerId) {
        tenantSession.bind();
        CustomerCart cart = cartRepository.findByCustomerId(customerId).orElse(null);
        if (cart == null) {
            return List.of();
        }
        List<CartLine> out = new ArrayList<>();
        for (CartItem item : itemRepository.findByCartIdOrderByAddedAt(cart.getId())) {
            productRepository.findById(item.getProductId())
                    .ifPresent(p -> out.add(new CartLine(item, p)));
        }
        return out;
    }

    // ----- helpers -----------------------------------------------------------

    private CartSnapshot snapshot(CustomerCart cart) {
        BigDecimal subtotal = BigDecimal.ZERO;
        int itemCount = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (CartItem item : itemRepository.findByCartIdOrderByAddedAt(cart.getId())) {
            Product p = productRepository.findById(item.getProductId()).orElse(null);
            if (p == null) {
                continue;
            }
            BigDecimal price = p.getPrice() == null ? BigDecimal.ZERO : p.getPrice();
            BigDecimal lineTotal = price.multiply(BigDecimal.valueOf(item.getQuantity()));
            subtotal = subtotal.add(lineTotal);
            itemCount += item.getQuantity();
            Map<String, Object> row = new HashMap<>();
            row.put("productId", p.getId());
            row.put("nameGeneric", p.getNameGeneric() == null ? p.getName() : p.getNameGeneric());
            row.put("nameBrand", p.getNameBrand());
            row.put("price", price);
            row.put("quantity", item.getQuantity());
            row.put("requiresPrescription", p.isRequiresPrescription());
            row.put("image", p.getImages() == null || p.getImages().isEmpty()
                    ? null : p.getImages().get(0));
            items.add(row);
        }
        return new CartSnapshot(items, subtotal, itemCount);
    }

    // ----- records + exceptions ---------------------------------------------

    public record CartSnapshot(List<Map<String, Object>> items, BigDecimal subtotal, int itemCount) {
        public static CartSnapshot empty() {
            return new CartSnapshot(List.of(), BigDecimal.ZERO, 0);
        }
    }

    public record CartLine(CartItem item, Product product) {
    }

    public static class InsufficientStockException extends RuntimeException {
        private final UUID productId;
        private final int available;
        private final int requested;

        public InsufficientStockException(UUID productId, int available, int requested) {
            super("Quantity " + requested + " exceeds stock " + available);
            this.productId = productId;
            this.available = available;
            this.requested = requested;
        }

        public UUID getProductId() {
            return productId;
        }

        public int getAvailable() {
            return available;
        }

        public int getRequested() {
            return requested;
        }
    }
}
