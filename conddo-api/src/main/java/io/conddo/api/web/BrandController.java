package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.BrandService;
import io.conddo.core.service.BrandService.Brand;
import io.conddo.core.service.BrandService.BrandPatch;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant brand endpoint (§Website Provisioning). Called by:
 *   - dashboard Settings → Brand (owner-only PATCH),
 *   - the site renderer at {@code app/sites/[host]/page.tsx} to bind
 *     colours + logo to every rendered section (GET),
 *   - onboarding Step 5 to seed the brand from AI suggestions (PATCH).
 *
 * <p>PATCH accepts partial bodies so the FE can debounce field-by-field
 * updates without re-sending the whole brand on every keystroke.
 */
@RestController
@RequestMapping("/api/v1/brand")
public class BrandController {

    private final BrandService brandService;

    public BrandController(BrandService brandService) {
        this.brandService = brandService;
    }

    /** Read the current brand. Any authenticated tenant user may read —
     *  the site renderer needs it too and runs under the tenant's public
     *  session, not the owner's. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Brand> get() {
        return ApiResponse.ok(brandService.current());
    }

    /** Update any subset of the brand fields. Owner-only — the brand is
     *  billing-adjacent surface area (visible to every customer). */
    @PatchMapping
    @PreAuthorize("@staffAccess.ownerOnly()")
    public ApiResponse<Brand> patch(@RequestBody BrandPatch patch) {
        return ApiResponse.ok(brandService.update(patch));
    }
}
