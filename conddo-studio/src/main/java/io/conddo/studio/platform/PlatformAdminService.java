package io.conddo.studio.platform;

import io.conddo.studio.common.NotFoundException;
import io.conddo.studio.repository.JobRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read-only platform admin facade (Infrastructure §23 Phase 13a). Wraps the
 * {@link PlatformTenantRepository} + {@link PlatformUserRepository} mirror
 * entities with the search / detail surface the FE wires against; Phase 13b
 * adds the mutators on top.
 *
 * <p>Counts ({@code activeJobs}, {@code deliveredJobs}) come from the existing
 * Studio {@link JobRepository} — Studio admins want them on the tenant detail
 * card so they can see at a glance how much work that tenant has in flight.
 */
@Service
public class PlatformAdminService {

    private final PlatformTenantRepository tenants;
    private final PlatformUserRepository users;
    private final JobRepository jobs;

    public PlatformAdminService(PlatformTenantRepository tenants,
                                PlatformUserRepository users,
                                JobRepository jobs) {
        this.tenants = tenants;
        this.users = users;
        this.jobs = jobs;
    }

    // ----- tenants ------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<PlatformTenant> searchTenants(String q, String status, Pageable pageable) {
        return tenants.search(nullToBlank(q), nullToBlank(status), pageable);
    }

    @Transactional(readOnly = true)
    public TenantWithCounts getTenant(UUID tenantId) {
        PlatformTenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new NotFoundException("Tenant not found: " + tenantId));
        long totalUsers = users.countByTenantId(tenantId);
        long activeUsers = users.countByTenantIdAndActiveTrue(tenantId);
        // Studio job counts — the platform tenant's footprint inside the Studio pipeline.
        long activeJobs = jobs.findByStatusInOrderBySlaDeadlineAsc(
                java.util.List.of("AVAILABLE", "ASSIGNED", "IN_PROGRESS", "SUBMITTED", "IN_REVIEW", "REVISION"))
                .stream().filter(j -> tenantId.equals(j.getTenantId())).count();
        long deliveredJobs = jobs.findByStatusInOrderBySlaDeadlineAsc(
                java.util.List.of("APPROVED", "DELIVERED"))
                .stream().filter(j -> tenantId.equals(j.getTenantId())).count();
        return new TenantWithCounts(tenant, totalUsers, activeUsers, activeJobs, deliveredJobs);
    }

    // ----- users --------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<PlatformUser> searchUsers(String q, UUID tenantId, String role, Pageable pageable) {
        return users.search(nullToBlank(q), tenantId, nullToBlank(role), pageable);
    }

    @Transactional(readOnly = true)
    public List<PlatformUser> listUsersForTenant(UUID tenantId) {
        // 404 the tenant rather than returning an empty list — clearer for the admin UI.
        if (!tenants.existsById(tenantId)) {
            throw new NotFoundException("Tenant not found: " + tenantId);
        }
        return users.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Transactional(readOnly = true)
    public UserWithTenant getUser(UUID userId) {
        PlatformUser user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        PlatformTenant tenant = tenants.findById(user.getTenantId())
                .orElseThrow(() -> new NotFoundException("Tenant not found for user: " + userId));
        return new UserWithTenant(user, tenant);
    }

    // ----- view records (service-internal) ------------------------------------

    public record TenantWithCounts(PlatformTenant tenant, long totalUsers, long activeUsers,
                                   long activeJobs, long deliveredJobs) {
    }

    public record UserWithTenant(PlatformUser user, PlatformTenant tenant) {
    }

    /** Use empty string for "unset" so JDBC binds it as TEXT, not bytea. */
    private static String nullToBlank(String s) {
        return s == null ? "" : s.trim();
    }
}
