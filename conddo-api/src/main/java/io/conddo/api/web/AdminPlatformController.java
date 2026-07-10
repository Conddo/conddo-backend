package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.PlatformOverviewService;
import io.conddo.core.service.PlatformOverviewService.Overview;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Studio dashboard snapshot — cross-tenant read of the current platform
 * state. SUPER_ADMIN only. Feeds the single-page Studio dashboard at
 * {@code studio.getconddo.com}.
 */
@RestController
@RequestMapping("/api/v1/admin/platform")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminPlatformController {

    private final PlatformOverviewService service;

    public AdminPlatformController(PlatformOverviewService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<Overview> overview() {
        return ApiResponse.ok(service.snapshot());
    }
}
