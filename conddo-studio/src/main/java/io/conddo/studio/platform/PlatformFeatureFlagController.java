package io.conddo.studio.platform;

import io.conddo.studio.auth.StudioPrincipal;
import io.conddo.studio.common.ApiResponse;
import io.conddo.studio.platform.PlatformFeatureFlagService.Row;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Studio cross-tenant feature-flag review queue
 * (HANDOFF_2026-06-12b §2). FE at
 * {@code /admin/platform/feature-flags} is wired to this.
 *
 * <ul>
 *   <li>{@code GET} — list, gated to ADMIN + TEAM_LEAD (read).</li>
 *   <li>{@code POST .../grant}, {@code POST .../revoke} — ADMIN only.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/jobs/admin/platform/feature-flags")
@PreAuthorize("hasAnyRole('ADMIN','TEAM_LEAD')")
public class PlatformFeatureFlagController {

    private final PlatformFeatureFlagService service;

    @PersistenceContext
    private EntityManager em;

    public PlatformFeatureFlagController(PlatformFeatureFlagService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "featureKey", required = false) String featureKey) {
        return ApiResponse.ok(service.list(status, featureKey).stream()
                .map(Row::toMap)
                .toList());
    }

    @PostMapping("/{tenantId}/{featureKey}/grant")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> grant(@PathVariable UUID tenantId,
                                                   @PathVariable String featureKey,
                                                   @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = StudioPrincipal.staffId(jwt);
        return ApiResponse.ok(service.grant(tenantId, featureKey, actorId).toMap());
    }

    @PostMapping("/{tenantId}/{featureKey}/revoke")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> revoke(@PathVariable UUID tenantId,
                                                    @PathVariable String featureKey) {
        return ApiResponse.ok(service.revoke(tenantId, featureKey).toMap());
    }

    /**
     * One-shot diagnostic — returns what this Studio connection actually
     * sees in {@code public.tenant_feature_flags}. Used to triage the
     * "tenant says request submitted, Studio queue empty" failure mode
     * without needing DB shell access.
     *
     * <p>Reports:
     * <ul>
     *   <li>The DB the Studio process is connected to ({@code current_database()}).</li>
     *   <li>The Postgres role this connection is authenticated as.</li>
     *   <li>Whether the {@code app.cross_tenant} session var is set — this
     *       is the RLS carve-out the Hikari init-SQL is supposed to flip on.</li>
     *   <li>Raw row count + interest count + a sample of the most-recent
     *       interest rows (no joins, no RLS-derived bucketing — just what
     *       Postgres returns for an unconditional SELECT).</li>
     * </ul>
     *
     * <p>ADMIN only. Safe to leave in long-term — it's read-only and useful
     * any time the queue looks off.
     */
    @GetMapping("/_debug")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public ApiResponse<Map<String, Object>> debug() {
        Map<String, Object> out = new LinkedHashMap<>();

        Object[] env = (Object[]) em.createNativeQuery(
                "SELECT current_database(), current_user, " +
                        "current_setting('app.cross_tenant', true), " +
                        "current_setting('app.tenant_id', true)").getSingleResult();
        out.put("database", env[0]);
        out.put("dbRole", env[1]);
        out.put("crossTenantSet", env[2]);
        out.put("tenantIdSet", env[3]);

        Number total = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM public.tenant_feature_flags").getSingleResult();
        out.put("totalRows", total.longValue());

        Number interest = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM public.tenant_feature_flags " +
                        "WHERE interest = true AND enabled = false AND granted_at IS NULL")
                .getSingleResult();
        out.put("interestRows", interest.longValue());

        @SuppressWarnings("unchecked")
        List<Object[]> sample = em.createNativeQuery(
                "SELECT tenant_id, feature_key, status, interest, enabled, " +
                        "interest_at, granted_at " +
                        "FROM public.tenant_feature_flags " +
                        "ORDER BY interest_at DESC NULLS LAST " +
                        "LIMIT 5").getResultList();
        List<Map<String, Object>> sampleRows = sample.stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tenantId", r[0]);
                    m.put("featureKey", r[1]);
                    m.put("status", r[2]);
                    m.put("interest", r[3]);
                    m.put("enabled", r[4]);
                    m.put("interestAt", r[5]);
                    m.put("grantedAt", r[6]);
                    return m;
                })
                .toList();
        out.put("sample", sampleRows);
        return ApiResponse.ok(out);
    }
}
