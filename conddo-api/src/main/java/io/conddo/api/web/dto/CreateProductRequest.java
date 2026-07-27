package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Create an inventory product (§11.6). {@code expiryDate} + {@code batchNumber}
 * are pharmacy-specific (PHARMACY_DEEP_DIVE_SPEC §2) and optional — non-pharmacy
 * verticals omit them. {@code images} are Cloudinary URLs surfaced on the
 * public catalog.
 */
public record CreateProductRequest(
        @NotBlank String name,
        String sku,
        UUID categoryId,
        BigDecimal price,
        Integer stock,
        Integer reorderThreshold,
        Boolean active,
        LocalDate expiryDate,
        String batchNumber,
        List<String> images
) {
}
