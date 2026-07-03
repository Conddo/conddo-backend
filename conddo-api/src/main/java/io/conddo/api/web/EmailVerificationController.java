package io.conddo.api.web;

import io.conddo.core.auth.EmailVerificationService;
import io.conddo.core.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Email verification endpoints (Onboarding v2).
 *
 * <ul>
 *   <li>{@code POST /auth/verify-email} — public. Body carries the raw token
 *       from the emailed link; the FE's /verify-email page auto-POSTs on
 *       mount. Success is idempotent (already-verified users get 200 too).</li>
 *   <li>{@code POST /api/v1/me/verify-email/resend} — authenticated. Reissues
 *       the link for the caller's own user; used by the dashboard banner.
 *       Lives under {@code /api/v1/**} so SecurityConfig's default auth
 *       requirement applies without a bespoke rule.</li>
 * </ul>
 */
@RestController
public class EmailVerificationController {

    private final EmailVerificationService service;

    public EmailVerificationController(EmailVerificationService service) {
        this.service = service;
    }

    @PostMapping("/auth/verify-email")
    public ResponseEntity<ApiResponse<Void>> verify(@Valid @RequestBody VerifyEmailRequest body) {
        service.verify(body.token());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/api/v1/me/verify-email/resend")
    public ResponseEntity<ApiResponse<Void>> resend(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        service.resendForUser(userId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    public record VerifyEmailRequest(@NotBlank String token) {
    }
}
