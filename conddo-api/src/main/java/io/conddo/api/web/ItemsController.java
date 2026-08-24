package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.ItemsFeedService;
import io.conddo.core.service.ItemsFeedService.Item;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code GET /api/v1/items} — unified sellable-item feed for the mobile
 * app's Sell picker + Products list. Merges every vertical's product
 * surface (generic inventory + fashion) into one array. Tenant-scoped
 * by RLS.
 */
@RestController
@RequestMapping("/api/v1/items")
public class ItemsController {

    private final ItemsFeedService service;

    public ItemsController(ItemsFeedService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Item>> list() {
        return ApiResponse.ok(service.list());
    }
}
