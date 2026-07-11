package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.Tenant;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.service.ModuleResolver;
import io.conddo.core.service.ModuleResolver.ModuleState;
import io.conddo.core.tenant.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Per-tenant module opt-in surface (Vertical Inference Phase B).
 * Lets a tenant turn on a module not in their vertical's default
 * preset, or turn off one they don't use. The resolver feeds the
 * JWT activeModules claim, so changes take effect on the next login
 * (existing tokens keep the old claim until expiry).
 *
 * <p>Owner-only — module changes affect billing surface area and
 * shouldn't be staff-toggleable.
 */
@RestController
@RequestMapping("/api/v1/tenant/modules")
@PreAuthorize("@staffAccess.ownerOnly()")
public class TenantModulesController {

    private final ModuleResolver resolver;
    private final TenantRepository tenantRepository;

    public TenantModulesController(ModuleResolver resolver, TenantRepository tenantRepository) {
        this.resolver = resolver;
        this.tenantRepository = tenantRepository;
    }

    @GetMapping
    public ApiResponse<List<ModuleRow>> list() {
        Tenant tenant = currentTenant();
        return ApiResponse.ok(resolver.listAll(tenant.getVerticalId(), tenant.getPlanId()).stream()
                .map(TenantModulesController::toRow).toList());
    }

    /** Live effective module ids for the calling tenant — vertical/plan preset
     *  ∪ opt-ins − opt-outs. Widened past owner-only because every authenticated
     *  session needs it to build the sidebar (staff shouldn't see a tool their
     *  owner disabled). Overrides the class-level {@code ownerOnly()} gate. */
    @GetMapping("/active")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<String>> active() {
        Tenant tenant = currentTenant();
        return ApiResponse.ok(resolver.resolve(tenant.getVerticalId(), tenant.getPlanId()));
    }

    @PostMapping("/{moduleId}/enable")
    public ResponseEntity<ApiResponse<ModuleRow>> enable(@PathVariable String moduleId) {
        resolver.setEnabled(moduleId, true);
        return ResponseEntity.ok(ApiResponse.ok(stateRow(moduleId)));
    }

    @PostMapping("/{moduleId}/disable")
    public ResponseEntity<ApiResponse<ModuleRow>> disable(@PathVariable String moduleId) {
        resolver.setEnabled(moduleId, false);
        return ResponseEntity.ok(ApiResponse.ok(stateRow(moduleId)));
    }

    // ----- helpers ----------------------------------------------------------

    private Tenant currentTenant() {
        return tenantRepository.findById(TenantContext.require())
                .orElseThrow(() -> new io.conddo.core.common.NotFoundException("Tenant not found"));
    }

    private ModuleRow stateRow(String moduleId) {
        Tenant tenant = currentTenant();
        return resolver.listAll(tenant.getVerticalId(), tenant.getPlanId()).stream()
                .filter(s -> s.id().equals(moduleId))
                .findFirst()
                .map(TenantModulesController::toRow)
                .orElseGet(() -> new ModuleRow(moduleId, true, false, "tenant_choice"));
    }

    private static ModuleRow toRow(ModuleState state) {
        return new ModuleRow(state.id(), state.enabled(),
                state.inVerticalDefault(), state.source());
    }

    /**
     * API contract for a single module row. Typed record instead of the
     * previous {@code Map<String,Object>} — Jackson serialises the fields in
     * declaration order, the FE gets a strong type, and OpenAPI generators
     * can now emit a schema.
     */
    public record ModuleRow(String id, boolean enabled, boolean inVerticalDefault, String source) {}
}
