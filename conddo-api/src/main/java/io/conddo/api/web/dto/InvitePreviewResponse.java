package io.conddo.api.web.dto;

import io.conddo.core.auth.StaffInviteService.InvitePreview;

/**
 * Preview shape for GET /auth/invite/preview (HANDOFF_2026-06-12 §5).
 * The FE uses this to render "You've been invited to X as Y" before
 * collecting a password.
 */
public record InvitePreviewResponse(String tenantName, String roleLabel, String staffRole,
                                     String email, String invitedBy) {

    public static InvitePreviewResponse from(InvitePreview p) {
        return new InvitePreviewResponse(p.tenantName(), p.roleLabel(), p.staffRole(),
                p.email(), p.invitedByName());
    }
}
