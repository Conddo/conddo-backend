package io.conddo.api.security;

import io.conddo.core.auth.StaffAccessMatrix;
import io.conddo.core.auth.StaffAccessMatrix.Permission;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Spring-Security SpEL hook for {@code @PreAuthorize} that wraps
 * {@link StaffAccessMatrix} — the matrix data lives in conddo-core
 * so {@code StaffService.roles()} can reach it without depending on
 * the web layer; this bean is the request-time auth side.
 *
 * <pre>
 * &#64;PreAuthorize("&#64;staffAccess.canWrite('inventory')")
 * &#64;PostMapping("/restock")
 * public ResponseEntity&lt;...&gt; restock(...) { ... }
 * </pre>
 *
 * <p>Owners and SUPER_ADMIN bypass the matrix entirely; STAFF users
 * are gated by their {@code staffRole} JWT claim.
 */
@Component("staffAccess")
public class StaffAccess {

    public boolean canRead(String module) {
        return permissionFor(module).reads();
    }

    public boolean canWrite(String module) {
        return permissionFor(module).writes();
    }

    /** Owner-only convenience (billing, staff invites, discount approval, POS void). */
    public boolean ownerOnly() {
        String role = currentPlatformRole();
        return "TENANT_ADMIN".equals(role) || "SUPER_ADMIN".equals(role);
    }

    /** Owner or a specific staff sub-role; e.g. EMR notes = PHARMACIST+MANAGER+owner. */
    public boolean ownerOr(String... staffRoles) {
        if (ownerOnly()) {
            return true;
        }
        String myStaffRole = currentStaffRole();
        if (myStaffRole == null) {
            return false;
        }
        for (String role : staffRoles) {
            if (myStaffRole.equals(role)) {
                return true;
            }
        }
        return false;
    }

    // ----- internals ---------------------------------------------------------

    private Permission permissionFor(String module) {
        String role = currentPlatformRole();
        if ("SUPER_ADMIN".equals(role) || "TENANT_ADMIN".equals(role)) {
            return Permission.WRITE;
        }
        if (!"STAFF".equals(role)) {
            return Permission.NONE;
        }
        return StaffAccessMatrix.permissionFor(currentStaffRole(), module);
    }

    private String currentPlatformRole() {
        Jwt jwt = currentJwt();
        return jwt == null ? null : jwt.getClaimAsString("role");
    }

    private String currentStaffRole() {
        Jwt jwt = currentJwt();
        return jwt == null ? null : jwt.getClaimAsString("staffRole");
    }

    private Jwt currentJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        Object principal = auth.getPrincipal();
        return principal instanceof Jwt jwt ? jwt : null;
    }
}
