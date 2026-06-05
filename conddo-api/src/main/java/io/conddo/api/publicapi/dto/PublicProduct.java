package io.conddo.api.publicapi.dto;

import io.conddo.core.domain.Product;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Public-safe product shape (WEBSITE_INTEGRATION_SPEC §3). Strips internal
 * fields ({@code reorderThreshold}, {@code cost}, expiry batches, etc.) and
 * exposes {@code stockAvailable} as a boolean — shopping carts don't need
 * the raw integer and leaking it bleeds business intel.
 */
public record PublicProduct(
        UUID id,
        String name,
        String sku,
        BigDecimal price,
        boolean stockAvailable,
        String imageUrl) {

    public static PublicProduct from(Product p) {
        return new PublicProduct(
                p.getId(),
                p.getName(),
                p.getSku(),
                p.getPrice(),
                p.getStock() > 0,
                null);   // image_url is a Phase 2 follow-up (media linkage)
    }
}
