package io.conddo.api.publicapi;

import io.conddo.core.auth.CustomerJwtService;
import io.conddo.core.service.PublicCartService;
import io.conddo.core.service.PublicCartService.CartSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Customer cart (PHARMACY_PUBLIC_API_SPEC §4). Server-side; survives
 * across devices for a logged-in customer. POST replaces quantity per
 * the spec.
 */
@RestController
@RequestMapping("/api/v1/public/{slug}/pharmacy/cart")
public class PublicCartController {

    private final PublicCartService cartService;
    private final CustomerJwtService customerJwtService;

    public PublicCartController(PublicCartService cartService,
                                CustomerJwtService customerJwtService) {
        this.cartService = cartService;
        this.customerJwtService = customerJwtService;
    }

    @GetMapping
    public Map<String, Object> read(HttpServletRequest request) {
        UUID customerId = PublicCustomerAuth.requireCustomerId(request, customerJwtService);
        return Map.of("cart", toCartShape(cartService.read(customerId)));
    }

    @PostMapping
    public Map<String, Object> upsert(@Valid @RequestBody UpsertCartItemRequest body,
                                      HttpServletRequest request) {
        UUID customerId = PublicCustomerAuth.requireCustomerId(request, customerJwtService);
        return Map.of("cart",
                toCartShape(cartService.upsertItem(customerId, body.productId(), body.quantity())));
    }

    @DeleteMapping("/{productId}")
    public Map<String, Object> removeItem(@PathVariable UUID productId,
                                          HttpServletRequest request) {
        UUID customerId = PublicCustomerAuth.requireCustomerId(request, customerJwtService);
        return Map.of("cart", toCartShape(cartService.removeItem(customerId, productId)));
    }

    @DeleteMapping
    public Map<String, Object> clear(HttpServletRequest request) {
        UUID customerId = PublicCustomerAuth.requireCustomerId(request, customerJwtService);
        cartService.clear(customerId);
        return Map.of("success", true);
    }

    private static Map<String, Object> toCartShape(CartSnapshot snap) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("items", snap.items());
        m.put("subtotal", snap.subtotal());
        m.put("itemCount", snap.itemCount());
        return m;
    }

    public record UpsertCartItemRequest(@NotNull UUID productId, @Positive int quantity) {
    }
}
