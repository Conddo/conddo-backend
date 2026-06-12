package io.conddo.api.web;

import io.conddo.api.security.RefreshCookies;
import io.conddo.api.web.dto.AcceptInviteRequest;
import io.conddo.api.web.dto.InvitePreviewResponse;
import io.conddo.api.web.dto.LoginResponse;
import io.conddo.core.auth.AuthResult;
import io.conddo.core.auth.StaffInviteService;
import io.conddo.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pre-auth invite acceptance (HANDOFF_2026-06-12 §5). Both endpoints
 * are permitAll'd by {@code /auth/**} in SecurityConfig and take a
 * single-use signed token from the invite email.
 */
@RestController
@RequestMapping("/auth")
public class InviteAcceptanceController {

    private final StaffInviteService inviteService;
    private final RefreshCookies refreshCookies;

    public InviteAcceptanceController(StaffInviteService inviteService, RefreshCookies refreshCookies) {
        this.inviteService = inviteService;
        this.refreshCookies = refreshCookies;
    }

    @GetMapping("/invite/preview")
    public ApiResponse<InvitePreviewResponse> preview(@RequestParam("token") String token) {
        return ApiResponse.ok(InvitePreviewResponse.from(inviteService.previewInvite(token)));
    }

    @PostMapping("/accept-invite")
    public ResponseEntity<ApiResponse<LoginResponse>> accept(
            @Valid @RequestBody AcceptInviteRequest body) {
        AuthResult result = inviteService.acceptInvite(body.token(), body.password(), body.fullName());
        ResponseCookie cookie = refreshCookies.issue(result.refreshToken(), result.refreshTokenTtl());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.ok(LoginResponse.from(result)));
    }
}
