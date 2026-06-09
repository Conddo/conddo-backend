package io.conddo.api.web.dto;

import io.conddo.core.domain.ProductCategory;
import io.conddo.core.service.InventoryService.CategoryWithCount;

import java.util.UUID;

/**
 * An inventory category (§11.6). {@code productCount} is populated
 * for the list + write endpoints used by the dashboard category
 * manager (HANDOFF_2026-06-09 §2.3); it stays {@code null} on the
 * legacy create-only path so wire shapes don't change.
 */
public record CategoryDto(UUID id, String name, Integer productCount) {

    public static CategoryDto from(ProductCategory c) {
        return new CategoryDto(c.getId(), c.getName(), null);
    }

    public static CategoryDto from(CategoryWithCount view) {
        return new CategoryDto(view.category().getId(),
                view.category().getName(), view.productCount());
    }
}
