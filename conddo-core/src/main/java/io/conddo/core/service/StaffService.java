package io.conddo.core.service;

import io.conddo.core.auth.PasswordHasher;
import io.conddo.core.auth.StaffInviteService;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.AuditLog;
import io.conddo.core.domain.User;
import io.conddo.core.repository.AuditLogRepository;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Staff management (§11.10): the tenant's users (TENANT_ADMIN / STAFF) over the
 * existing RLS-scoped {@code users} table — no new table. Admin-only. An invited
 * user is created without a usable password and sets one via the standard
 * password-reset flow (their email + business slug); deactivation is enforced at
 * login by {@code AuthService}. Per-user activity reads the audit log (§3).
 */
@Service
public class StaffService {

    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final TenantSession tenantSession;
    private final StaffInviteService inviteService;

    public StaffService(UserRepository userRepository, AuditLogRepository auditLogRepository,
                        TenantSession tenantSession, StaffInviteService inviteService) {
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
        this.tenantSession = tenantSession;
        this.inviteService = inviteService;
    }

    @Transactional(readOnly = true)
    public List<User> list() {
        tenantSession.bind();
        return userRepository.findAll();
    }

    @Transactional
    public User invite(String email, String staffRole, String fullName, UUID invitedByUserId) {
        return inviteService.invite(email, staffRole, fullName, invitedByUserId);
    }

    @Transactional(readOnly = true)
    public User get(UUID id) {
        tenantSession.bind();
        return require(id);
    }

    @Transactional
    public User update(UUID id, String staffRole, Boolean active) {
        tenantSession.bind();
        User user = require(id);
        // The owner row (TENANT_ADMIN) is never editable through this
        // surface — defence in depth, the FE also gates it.
        if ("TENANT_ADMIN".equals(user.getRole())) {
            throw new OwnerProtectedException();
        }
        if (staffRole != null) {
            user.changeStaffRole(StaffInviteService.normaliseStaffRole(staffRole));
        }
        if (active != null) {
            user.setActive(active);
        }
        return userRepository.save(user);
    }

    @Transactional
    public void resendInvite(UUID id) {
        inviteService.resendInvite(id);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> activity(UUID id) {
        tenantSession.bind();
        require(id);
        return auditLogRepository.findTop50ByUserIdOrderByCreatedAtDesc(id);
    }

    /**
     * Sub-role catalogue (HANDOFF_2026-06-12 §1). Matches FE
     * STAFF_ROLE_CATALOGUE in lib/api/staff.ts. BE is authoritative on
     * the 403; FE catalogue is a UI mirror.
     */
    public List<RoleDef> roles() {
        return List.of(
                new RoleDef("MANAGER", "Manager",
                        List.of("Everything except billing + staff invites",
                                "Inventory, sales, orders, customers, analytics")),
                new RoleDef("PHARMACIST", "Pharmacist",
                        List.of("Clinical access: EMR, prescriptions, consultations",
                                "Read-only inventory, orders, customers, analytics")),
                new RoleDef("CASHIER", "Cashier",
                        List.of("POS sales (open shifts, run sales, take payments)",
                                "Read-only customers, orders, payments, inventory")),
                new RoleDef("STOCK_MANAGER", "Stock Manager",
                        List.of("Inventory: restock, reconciliation, bulk upload, movement log",
                                "Read-only orders, customers, analytics")),
                new RoleDef("BOOKKEEPER", "Bookkeeper",
                        List.of("Read-only orders, payments, analytics, customers",
                                "CSV exports for reconciliation")));
    }

    // ----- internals ----------------------------------------------------------

    private User require(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Staff member not found"));
    }

    /** A sub-role plus a human-readable access summary. */
    public record RoleDef(String role, String label, List<String> permissions) {
    }

    public static class OwnerProtectedException extends RuntimeException {
        public OwnerProtectedException() {
            super("The owner account cannot be modified through this surface");
        }
    }
}
