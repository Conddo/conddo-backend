package io.conddo.studio.common;

/**
 * Refuses to demote, deactivate, or delete the last active TENANT_ADMIN of a
 * tenant (§23.5). Mapped to 422 {@code LAST_ADMIN_PROTECTED} so the Studio UI
 * can prompt the operator to promote another user first.
 */
public class LastAdminProtectedException extends RuntimeException {
    public LastAdminProtectedException() {
        super("A tenant must keep at least one active TENANT_ADMIN");
    }
}
