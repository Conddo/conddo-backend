package io.conddo.core.service;

import io.conddo.core.domain.TenantModuleOverride;
import io.conddo.core.registry.VerticalDataLoader;
import io.conddo.core.registry.VerticalDefinition;
import io.conddo.core.registry.VerticalToolMatrix;
import io.conddo.core.repository.TenantModuleOverrideRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Effective module set for a tenant = vertical/plan default
 * (from {@link VerticalToolMatrix}) ∪ tenant opt-ins − tenant
 * opt-outs (from {@code tenant_module_overrides}, V51).
 *
 * <p>This replaces direct {@code toolMatrix.resolve(...)} calls in
 * auth flows so a pharmacy that opts into {@code fittings.fashion}
 * gets it on their JWT, and a tenant that opts out of
 * {@code marketing.email} doesn't.
 */
@Service
public class ModuleResolver {

    private final VerticalToolMatrix toolMatrix;
    private final TenantModuleOverrideRepository overrides;
    private final VerticalDataLoader verticals;
    private final TenantSession tenantSession;

    public ModuleResolver(VerticalToolMatrix toolMatrix,
                          TenantModuleOverrideRepository overrides,
                          VerticalDataLoader verticals,
                          TenantSession tenantSession) {
        this.toolMatrix = toolMatrix;
        this.overrides = overrides;
        this.verticals = verticals;
        this.tenantSession = tenantSession;
    }

    /**
     * Final module set for the bound tenant. {@code resolve} requires
     * an already-bound tenant context so the override lookup is
     * RLS-scoped.
     */
    @Transactional(readOnly = true)
    public List<String> resolve(String vertical, String plan) {
        tenantSession.bind();
        Set<String> result = new LinkedHashSet<>(toolMatrix.resolve(vertical, plan));
        for (TenantModuleOverride ovr : overrides.findAll()) {
            if (ovr.isEnabled()) {
                result.add(ovr.getModuleId());
            } else {
                result.remove(ovr.getModuleId());
            }
        }
        return List.copyOf(result);
    }

    /**
     * Listing view for the FE picker: every module known to any
     * vertical, with its current state for the bound tenant.
     */
    @Transactional(readOnly = true)
    public List<ModuleState> listAll(String vertical, String plan) {
        tenantSession.bind();
        Set<String> defaults = new LinkedHashSet<>(toolMatrix.resolve(vertical, plan));
        Map<String, TenantModuleOverride> overrideMap = new LinkedHashMap<>();
        for (TenantModuleOverride ovr : overrides.findAll()) {
            overrideMap.put(ovr.getModuleId(), ovr);
        }
        Set<String> ids = new TreeSet<>();
        for (VerticalDefinition def : verticals.all().values()) {
            ids.addAll(def.starterTools());
            ids.addAll(def.businessToolsAdd());
            ids.addAll(def.proToolsAdd());
        }
        ids.addAll(overrideMap.keySet());

        return ids.stream()
                .map(id -> {
                    TenantModuleOverride ovr = overrideMap.get(id);
                    boolean inDefault = defaults.contains(id);
                    boolean enabled = ovr == null ? inDefault : ovr.isEnabled();
                    String source = ovr == null ? "vertical_default" : "tenant_choice";
                    return new ModuleState(id, enabled, inDefault, source);
                })
                .toList();
    }

    @Transactional
    public TenantModuleOverride setEnabled(String moduleId, boolean enabled) {
        tenantSession.bind();
        TenantModuleOverride existing = overrides.findByModuleId(moduleId).orElse(null);
        if (existing == null) {
            return overrides.save(new TenantModuleOverride(TenantContext.require(), moduleId, enabled));
        }
        existing.setEnabled(enabled);
        return overrides.save(existing);
    }

    public record ModuleState(String id, boolean enabled, boolean inVerticalDefault, String source) {
    }
}
