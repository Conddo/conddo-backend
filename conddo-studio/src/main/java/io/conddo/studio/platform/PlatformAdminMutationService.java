package io.conddo.studio.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.studio.common.LastAdminProtectedException;
import io.conddo.studio.common.NotFoundException;
import io.conddo.studio.sse.JobLifecycleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 13b mutations over the {@link PlatformTenant} / {@link PlatformUser}
 * mirror entities (§23.3). Every PATCH/DELETE here:
 * <ol>
 *   <li>Captures before-state for the audit row.</li>
 *   <li>Applies the change.</li>
 *   <li>Runs the integrity guards (§23.5 — last-admin protection).</li>
 *   <li>Cascades side effects: refresh-token revocation, password reset.</li>
 *   <li>Writes a {@link PlatformAdminAudit} row.</li>
 *   <li>Publishes the matching SSE event (AFTER_COMMIT via {@link JobLifecycleEvent}).</li>
 * </ol>
 *
 * <p>Refresh-token revocation is direct SQL into {@code public.refresh_tokens}
 * because Studio runs as the owner role (which owns that table). No reach-back
 * into {@code RefreshTokenService} — that lives in conddo-core which Studio
 * doesn't depend on.
 */
@Service
public class PlatformAdminMutationService {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminMutationService.class);

    private final PlatformTenantRepository tenants;
    private final PlatformUserRepository users;
    private final PlatformAdminAuditRepository audit;
    private final PlatformPasswordResetClient passwordReset;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;

    public PlatformAdminMutationService(PlatformTenantRepository tenants,
                                        PlatformUserRepository users,
                                        PlatformAdminAuditRepository audit,
                                        PlatformPasswordResetClient passwordReset,
                                        JdbcTemplate jdbc,
                                        ApplicationEventPublisher events,
                                        ObjectMapper objectMapper) {
        this.tenants = tenants;
        this.users = users;
        this.audit = audit;
        this.passwordReset = passwordReset;
        this.jdbc = jdbc;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    // ----- tenants -----------------------------------------------------------

    @Transactional
    public PlatformTenant patchTenant(UUID actorId, UUID tenantId, String name, String planId, String status) {
        PlatformTenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new NotFoundException("Tenant not found: " + tenantId));
        Map<String, Object> before = snapshotTenant(tenant);

        boolean statusChanged = false;
        String newStatus = null;
        if (name != null && !name.isBlank()) {
            tenant.rename(name);
        }
        if (planId != null && !planId.isBlank()) {
            tenant.setPlanId(planId);
        }
        if (status != null && !status.isBlank()) {
            String trimmed = status.trim().toUpperCase();
            if (!"ACTIVE".equals(trimmed) && !"SUSPENDED".equals(trimmed)) {
                throw new IllegalArgumentException("status must be ACTIVE or SUSPENDED, got: " + status);
            }
            if (!trimmed.equals(tenant.getStatus())) {
                tenant.setStatus(trimmed);
                statusChanged = true;
                newStatus = trimmed;
            }
        }
        PlatformTenant saved = tenants.save(tenant);
        Map<String, Object> after = snapshotTenant(saved);

        // Suspending kills every user's refresh-token family in that tenant.
        if (statusChanged && "SUSPENDED".equals(newStatus)) {
            int revoked = jdbc.update(
                    "UPDATE public.refresh_tokens SET revoked_at = now(), "
                            + "revoked_reason = 'PLATFORM_ADMIN_TENANT_SUSPENDED' "
                            + "WHERE tenant_id = ? AND revoked_at IS NULL",
                    tenantId);
            log.info("Tenant {} suspended — revoked {} refresh token(s)", tenantId, revoked);
        }

        audit(actorId, "TENANT_PATCH", "TENANT", tenantId, before, after);
        if (statusChanged) {
            events.publishEvent(JobLifecycleEvent.PlatformTenantStatusChanged.of(tenantId, newStatus, actorId));
        }
        return saved;
    }

    /** Soft-delete: flips status to {@code DELETED}. Refresh tokens revoked. */
    @Transactional
    public void softDeleteTenant(UUID actorId, UUID tenantId) {
        PlatformTenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new NotFoundException("Tenant not found: " + tenantId));
        Map<String, Object> before = snapshotTenant(tenant);
        tenant.setStatus("DELETED");
        PlatformTenant saved = tenants.save(tenant);
        Map<String, Object> after = snapshotTenant(saved);

        int revoked = jdbc.update(
                "UPDATE public.refresh_tokens SET revoked_at = now(), "
                        + "revoked_reason = 'PLATFORM_ADMIN_TENANT_DELETED' "
                        + "WHERE tenant_id = ? AND revoked_at IS NULL",
                tenantId);
        log.info("Tenant {} soft-deleted — revoked {} refresh token(s)", tenantId, revoked);

        audit(actorId, "TENANT_DELETE", "TENANT", tenantId, before, after);
        events.publishEvent(JobLifecycleEvent.PlatformTenantStatusChanged.of(tenantId, "DELETED", actorId));
    }

    // ----- users -------------------------------------------------------------

    @Transactional
    public PlatformUser patchUser(UUID actorId, UUID userId, String role, Boolean active, String fullName) {
        PlatformUser user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        Map<String, Object> before = snapshotUser(user);

        // Last-admin protection — must check BEFORE applying any mutation that
        // could orphan the tenant (demotion or deactivation).
        boolean demoting = role != null && !role.isBlank() && !role.equalsIgnoreCase("TENANT_ADMIN")
                && "TENANT_ADMIN".equals(user.getRole());
        boolean deactivating = Boolean.FALSE.equals(active) && user.isActive();
        if ((demoting || deactivating) && "TENANT_ADMIN".equals(user.getRole()) && user.isActive()) {
            long otherActiveAdmins = users.findByTenantIdOrderByCreatedAtDesc(user.getTenantId()).stream()
                    .filter(u -> !u.getId().equals(user.getId())
                            && "TENANT_ADMIN".equalsIgnoreCase(u.getRole())
                            && u.isActive())
                    .count();
            if (otherActiveAdmins == 0) {
                throw new LastAdminProtectedException();
            }
        }

        if (role != null && !role.isBlank()) {
            user.changeRole(role.trim().toUpperCase());
        }
        if (active != null) {
            user.setActive(active);
        }
        if (fullName != null && !fullName.isBlank()) {
            // PlatformUser has no setter for fullName — use a raw update so we can
            // change it without polluting the read-only entity with admin mutators
            // that aren't exposed elsewhere.
            jdbc.update("UPDATE public.users SET full_name = ? WHERE id = ?",
                    fullName.trim(), userId);
        }
        PlatformUser saved = users.save(user);
        // Re-read so the fullName update we did via raw SQL is in the response.
        if (fullName != null && !fullName.isBlank()) {
            saved = users.findById(userId).orElse(saved);
        }
        Map<String, Object> after = snapshotUser(saved);

        // Deactivating or role-changing revokes the user's refresh-token family.
        if (deactivating || demoting) {
            int revoked = jdbc.update(
                    "UPDATE public.refresh_tokens SET revoked_at = now(), "
                            + "revoked_reason = 'PLATFORM_ADMIN_USER_PATCH' "
                            + "WHERE user_id = ? AND revoked_at IS NULL",
                    userId);
            log.info("User {} patched (deactivating={}, demoting={}) — revoked {} refresh token(s)",
                    userId, deactivating, demoting, revoked);
            events.publishEvent(JobLifecycleEvent.PlatformUserDeactivated.of(userId, user.getTenantId(), actorId));
        }

        audit(actorId, "USER_PATCH", "USER", userId, before, after);
        return saved;
    }

    /** Soft-delete a user — deactivate + revoke all their refresh tokens. */
    @Transactional
    public void softDeleteUser(UUID actorId, UUID userId) {
        PlatformUser user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        // Last-admin guard also applies on delete.
        if ("TENANT_ADMIN".equals(user.getRole()) && user.isActive()) {
            long otherActiveAdmins = users.findByTenantIdOrderByCreatedAtDesc(user.getTenantId()).stream()
                    .filter(u -> !u.getId().equals(user.getId())
                            && "TENANT_ADMIN".equalsIgnoreCase(u.getRole())
                            && u.isActive())
                    .count();
            if (otherActiveAdmins == 0) {
                throw new LastAdminProtectedException();
            }
        }
        Map<String, Object> before = snapshotUser(user);
        user.setActive(false);
        PlatformUser saved = users.save(user);
        Map<String, Object> after = snapshotUser(saved);

        int revoked = jdbc.update(
                "UPDATE public.refresh_tokens SET revoked_at = now(), "
                        + "revoked_reason = 'PLATFORM_ADMIN_USER_DELETED' "
                        + "WHERE user_id = ? AND revoked_at IS NULL",
                userId);
        log.info("User {} soft-deleted — revoked {} refresh token(s)", userId, revoked);

        audit(actorId, "USER_DELETE", "USER", userId, before, after);
        events.publishEvent(JobLifecycleEvent.PlatformUserDeactivated.of(userId, user.getTenantId(), actorId));
    }

    /**
     * Triggers a password-reset email via the platform's existing
     * {@code /auth/forgot-password} endpoint. Returns {@code true} if the
     * platform accepted the request (i.e. emit a 202 to the operator); the
     * controller surfaces {@code false} as 502 PLATFORM_API_UNAVAILABLE.
     */
    @Transactional
    public boolean requestPasswordReset(UUID actorId, UUID userId) {
        PlatformUser user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        PlatformTenant tenant = tenants.findById(user.getTenantId())
                .orElseThrow(() -> new NotFoundException("Tenant not found for user: " + userId));
        boolean accepted = passwordReset.requestReset(tenant.getSlug(), user.getEmail());
        audit(actorId, "USER_PASSWORD_RESET", "USER", userId,
                Map.of("email", user.getEmail()),
                Map.of("accepted", accepted, "tenantSlug", tenant.getSlug()));
        return accepted;
    }

    // ----- audit helpers -----------------------------------------------------

    private void audit(UUID actorId, String action, String targetKind, UUID targetId,
                       Map<String, Object> before, Map<String, Object> after) {
        try {
            audit.save(new PlatformAdminAudit(actorId, action, targetKind, targetId,
                    objectMapper.valueToTree(before),
                    objectMapper.valueToTree(after),
                    null));
        } catch (RuntimeException ex) {
            // Audit must not break the mutation — surface the change, log the failure.
            log.error("Failed to record platform-admin audit row for {} {} {}: {}",
                    action, targetKind, targetId, ex.getMessage());
        }
    }

    private static Map<String, Object> snapshotTenant(PlatformTenant t) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", t.getName());
        m.put("slug", t.getSlug());
        m.put("planId", t.getPlanId());
        m.put("status", t.getStatus());
        return m;
    }

    private static Map<String, Object> snapshotUser(PlatformUser u) {
        Map<String, Object> m = new HashMap<>();
        m.put("email", u.getEmail());
        m.put("role", u.getRole());
        m.put("active", u.isActive());
        m.put("fullName", u.getFullName());
        return m;
    }
}
