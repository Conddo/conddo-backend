package io.conddo.api.publicapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.UUID;

/** Body for {@code POST /public/{slug}/pharmacy/orders}. */
public record PublicOrderRequest(
        @NotEmpty List<Item> items,
        @NotNull Customer customer,
        String deliveryAddress) {

    public record Item(@NotNull UUID productId, @Positive int quantity) {
    }

    public record Customer(
            @NotBlank String fullName,
            @NotBlank String phone,
            String email,
            String notes) {
    }
}
