package io.conddo.api.web;

import io.conddo.api.web.dto.TenantSiteDto;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.service.TenantSiteService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-facing Website Integration endpoints (WEBSITE_INTEGRATION_SPEC §2).
 * Reads are open to TENANT_ADMIN + STAFF; the regenerate path is TENANT_ADMIN
 * only. {@code apiKey} (plaintext) is non-null only on the response of
 * regenerate — the FE keeps it in-memory and a page refresh loses it.
 */
@RestController
@RequestMapping("/api/v1/website/site")
public class TenantSiteController {

    private static final String READ = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";
    private static final String ADMIN_ONLY = "hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')";

    private final TenantSiteService service;

    public TenantSiteController(TenantSiteService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(READ)
    public ApiResponse<TenantSiteDto> get() {
        return ApiResponse.ok(service.currentSite()
                .map(TenantSiteDto::masked)
                .orElseThrow(() -> new NotFoundException("No site registered for this tenant yet")));
    }

    @PostMapping("/regenerate-key")
    @PreAuthorize(ADMIN_ONLY)
    public ResponseEntity<ApiResponse<TenantSiteDto>> regenerate() {
        TenantSiteService.KeyResult result = service.regenerateKey();
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.ok(
                TenantSiteDto.withPlaintext(result.site(), result.plaintextKey())));
    }
}
