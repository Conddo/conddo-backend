package io.conddo.api.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * PATCH an inventory product (§11.6). All optional — only sent fields change.
 * {@code clearExpiryDate=true} explicitly clears expiry (the FE sends this
 * when the user wipes the date in the edit form). {@code images} is a full
 * replacement list of Cloudinary URLs (send empty list to clear).
 */
public record UpdateProductRequest(
        String name,
        String sku,
        UUID categoryId,
        BigDecimal price,
        Integer stock,
        Integer reorderThreshold,
        Boolean active,
        LocalDate expiryDate,
        Boolean clearExpiryDate,
        String batchNumber,
        List<String> images
) {

    public boolean expiryDateKeyPresent() {
        return expiryDate != null || Boolean.TRUE.equals(clearExpiryDate);
    }
}
