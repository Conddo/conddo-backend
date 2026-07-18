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
     *
     * <p><b>Plan is a hard ceiling.</b> Even an opt-in override cannot
     * elevate a tenant onto an above-plan module — the override row is
     * intersected with {@code toolMatrix.resolve(vertical, plan)}, so
     * stale rows from a previous plan tier stay dormant until an upgrade.
     */
    @Transactional(readOnly = true)
    public List<String> resolve(String vertical, String plan) {
        tenantSession.bind();
        Set<String> planCeiling = new LinkedHashSet<>(toolMatrix.resolve(vertical, plan));
        Set<String> result = new LinkedHashSet<>(planCeiling);
        for (TenantModuleOverride ovr : overrides.findAll()) {
            if (ovr.isEnabled()) {
                // Above-plan opt-ins never take effect — a Starter tenant
                // cannot self-enable a Growth-only tool via an override.
                if (planCeiling.contains(ovr.getModuleId())) {
                    result.add(ovr.getModuleId());
                }
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

        // `inPlan` = would resolve() pick this module up on the current plan.
        // Rows outside the plan ceiling render as locked in the picker — a
        // user on Starter sees the Growth-only tool with a "Plan required"
        // hint instead of a broken toggle that silently no-ops.
        Set<String> planCeiling = defaults; // toolMatrix.resolve is cumulative-to-plan
        return ids.stream()
                .map(id -> {
                    TenantModuleOverride ovr = overrideMap.get(id);
                    boolean inDefault = defaults.contains(id);
                    boolean effectiveEnabled = ovr == null
                            ? inDefault
                            : (ovr.isEnabled() && planCeiling.contains(id));
                    boolean inPlan = planCeiling.contains(id);
                    String source = ovr == null ? "vertical_default" : "tenant_choice";
                    return new ModuleState(id, effectiveEnabled, inDefault, inPlan, source);
                })
                .toList();
    }

    /**
     * Toggle a module for the bound tenant. Enabling a module the tenant's
     * plan doesn't cover is refused with {@link ModuleAboveTenantPlanException}
     * so the FE can surface a "Plan required" message and offer an upgrade
     * link. Disabling stays permitted at every tier — the tenant might turn
     * off a starter default they don't want cluttering their sidebar.
     */
    @Transactional
    public TenantModuleOverride setEnabled(String moduleId, boolean enabled,
                                            String vertical, String plan) {
        if (enabled && !toolMatrix.resolve(vertical, plan).contains(moduleId)) {
            throw new ModuleAboveTenantPlanException(moduleId, plan);
        }
        return setEnabled(moduleId, enabled);
    }

    /** Legacy signature — kept so pre-existing callers compile while we
     *  migrate them. New code should call the plan-aware overload. */
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

    public record ModuleState(String id, boolean enabled, boolean inVerticalDefault,
                              boolean inPlan, String source) {
    }

    /** Thrown when a caller tries to enable a module their plan doesn't cover.
     *  Mapped to 402 PLAN_UPGRADE_REQUIRED in the web layer. */
    public static class ModuleAboveTenantPlanException extends RuntimeException {
        private final String moduleId;
        private final String plan;
        public ModuleAboveTenantPlanException(String moduleId, String plan) {
            super("Module '" + moduleId + "' is above the '" + plan + "' plan tier.");
            this.moduleId = moduleId;
            this.plan = plan;
        }
        public String getModuleId() { return moduleId; }
        public String getPlan() { return plan; }
    }
}
