package io.conddo.studio.platform;

import io.conddo.studio.common.NotFoundException;
import io.conddo.studio.domain.Staff;
import io.conddo.studio.repository.StaffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Studio's cross-tenant feature-flag review queue
 * (HANDOFF_2026-06-12b). Ops uses this to act on tenants who
 * clicked "Request Beta Access" on /features in conddo-app.
 *
 * <p>Status is derived per-row (the table stores raw flags, the
 * queue derives the lifecycle bucket):
 *
 * <ul>
 *   <li>{@code interest} — interest=true AND enabled=false AND granted_at IS NULL</li>
 *   <li>{@code granted} — enabled=true</li>
 *   <li>{@code revoked} — enabled=false AND granted_at IS NOT NULL</li>
 * </ul>
 *
 * <p>Other states (interest=false AND no grant history) are
 * "coming_soon" placeholder rows that shouldn't appear in the
 * review queue at all; the list filters them out.
 */
@Service
public class PlatformFeatureFlagService {

    public enum DerivedStatus { interest, granted, revoked, all }

    private final PlatformFeatureFlagRepository flags;
    private final PlatformTenantRepository tenants;
    private final StaffRepository staff;
    private final PlatformUserRepository users;

    public PlatformFeatureFlagService(PlatformFeatureFlagRepository flags,
                                       PlatformTenantRepository tenants,
                                       StaffRepository staff,
                                       PlatformUserRepository users) {
        this.flags = flags;
        this.tenants = tenants;
        this.staff = staff;
        this.users = users;
    }

    // ----- list -------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Row> list(String statusFilter, String featureKeyFilter) {
        DerivedStatus filter = parseStatus(statusFilter);
        List<PlatformFeatureFlag> raw = (featureKeyFilter == null || featureKeyFilter.isBlank())
                ? flags.findAllByOrderByInterestAtDesc()
                : flags.findByFeatureKeyOrderByInterestAtDesc(featureKeyFilter);
        return raw.stream()
                .map(this::toRow)
                .filter(r -> filter == DerivedStatus.all || filter.name().equals(r.status()))
                .toList();
    }

    // ----- grant / revoke ----------------------------------------------------

    @Transactional
    public Row grant(UUID tenantId, String featureKey, UUID actorId) {
        PlatformFeatureFlag flag = flags.findByTenantIdAndFeatureKey(tenantId, featureKey)
                .orElseGet(() -> flags.save(PlatformFeatureFlag.forGrant(tenantId, featureKey, "beta")));
        flag.grant(actorId, OffsetDateTime.now());
        return toRow(flags.save(flag));
    }

    @Transactional
    public Row revoke(UUID tenantId, String featureKey) {
        PlatformFeatureFlag flag = flags.findByTenantIdAndFeatureKey(tenantId, featureKey)
                .orElseThrow(() -> new NotFoundException("Feature flag not found: " + tenantId + "/" + featureKey));
        flag.revoke();
        return toRow(flags.save(flag));
    }

    // ----- internals --------------------------------------------------------

    private Row toRow(PlatformFeatureFlag flag) {
        String derived = derive(flag);
        PlatformTenant tenant = tenants.findById(flag.getTenantId()).orElse(null);
        String grantedByName = flag.getGrantedBy() == null ? null : resolveActorName(flag.getGrantedBy());
        return new Row(
                flag.getTenantId(),
                flag.getFeatureKey(),
                tenant == null ? null : tenant.getName(),
                tenant == null ? null : tenant.getSlug(),
                tenant == null ? null : tenant.getVerticalId(),
                tenant == null ? null : tenant.getPlanId(),
                derived,
                flag.getInterestAt(),
                flag.getGrantedAt(),
                grantedByName
        );
    }

    private static String derive(PlatformFeatureFlag flag) {
        if (flag.isEnabled()) {
            return "granted";
        }
        if (flag.getGrantedAt() != null) {
            return "revoked";
        }
        if (flag.isInterest()) {
            return "interest";
        }
        return "coming_soon";
    }

    /** Resolve a UUID against Studio staff first, then platform users. */
    private String resolveActorName(UUID actorId) {
        Staff s = staff.findById(actorId).orElse(null);
        if (s != null) {
            return s.getFullName();
        }
        PlatformUser u = users.findById(actorId).orElse(null);
        return u == null ? null : u.getFullName();
    }

    private static DerivedStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return DerivedStatus.interest;   // FE default per the handoff
        }
        try {
            return DerivedStatus.valueOf(raw.trim().toLowerCase());
        } catch (IllegalArgumentException ex) {
            return DerivedStatus.interest;
        }
    }

    // ----- DTO --------------------------------------------------------------

    public record Row(UUID tenantId, String featureKey, String tenantName, String tenantSlug,
                      String tenantVertical, String tenantPlan, String status,
                      OffsetDateTime interestAt, OffsetDateTime grantedAt,
                      String grantedByName) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tenantId", tenantId);
            m.put("featureKey", featureKey);
            m.put("tenantName", tenantName);
            m.put("tenantSlug", tenantSlug);
            m.put("tenantVertical", tenantVertical);
            m.put("tenantPlan", tenantPlan);
            m.put("status", status);
            m.put("interestAt", interestAt);
            m.put("grantedAt", grantedAt);
            m.put("grantedByName", grantedByName);
            return m;
        }
    }
}
