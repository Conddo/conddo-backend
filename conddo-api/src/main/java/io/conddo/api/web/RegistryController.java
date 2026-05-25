package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.registry.ManifestCatalogue;
import io.conddo.core.registry.UiManifest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Serves frontend UI manifests for a tenant's active tools (Architecture §16).
 * On login the client decodes the JWT's {@code activeModules} and calls
 * {@code GET /api/v1/registry/manifests?modules=…}; it then builds the sidebar,
 * routes, and dashboard widgets from the returned manifests — no hardcoded nav.
 * Static catalogue data (not tenant-scoped), but authentication is required.
 */
@RestController
@RequestMapping("/api/v1/registry")
public class RegistryController {

    private final ManifestCatalogue catalogue;

    public RegistryController(ManifestCatalogue catalogue) {
        this.catalogue = catalogue;
    }

    @GetMapping("/manifests")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')")
    public ApiResponse<List<UiManifest>> manifests(
            @RequestParam(name = "modules", required = false) List<String> modules) {
        return ApiResponse.ok(catalogue.forModules(modules == null ? List.of() : modules));
    }
}
