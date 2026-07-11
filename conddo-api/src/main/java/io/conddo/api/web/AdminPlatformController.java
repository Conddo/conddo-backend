package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.PlatformOverviewService;
import io.conddo.core.service.PlatformOverviewService.Overview;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin dashboard endpoints for {@code studio.getconddo.com}. SUPER_ADMIN-only.
 * Serves the platform snapshot the FE renders as the metrics row + breakdown
 * widgets on the /admin dashboard.
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
