package io.conddo.api.web;

import io.conddo.api.web.dto.TenantSiteDto;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.TenantSite;
import io.conddo.core.service.TenantSiteService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-facing Website Integration endpoints (WEBSITE_INTEGRATION_SPEC §2).
 * Reads are open to TENANT_ADMIN + STAFF; mutating paths are TENANT_ADMIN
 * only. {@code apiKey} (plaintext) is non-null only on the response of
 * {@code regenerate} — the FE keeps it in-memory and a page refresh loses it.
 *
 * <p>{@code PATCH} lets a merchant claim or rename their subdomain (defaults
 * to their tenant slug on first {@code regenerate}, so most merchants never
 * call this). {@code submit} flips the row from "in progress" to "awaiting
 * QA review" — actual activation requires the STAFF flow in
 * {@code AdminTenantSiteController}.
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

    /** Claim / rename the subdomain. Validates RFC-1035 + the reserved list. */
    @PatchMapping
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<TenantSiteDto> patch(@Valid @RequestBody UpdateSiteRequest body) {
        TenantSite updated = service.updateSubdomain(body.subdomain());
        return ApiResponse.ok(TenantSiteDto.masked(updated));
    }

    /**
     * Submit the built URL for STAFF review. The site stays {@code is_active=false}
     * + {@code qa_approved=false} until a reviewer calls
     * {@code POST /api/v1/admin/sites/{id}/approve}.
     */
    @PostMapping("/submit")
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<TenantSiteDto> submit(@Valid @RequestBody SubmitSiteRequest body) {
        TenantSite updated = service.submitForReview(body.submittedUrl());
        return ApiResponse.ok(TenantSiteDto.masked(updated));
    }

    public record UpdateSiteRequest(@NotBlank String subdomain) {
    }

    public record SubmitSiteRequest(@NotBlank String submittedUrl) {
    }
}
