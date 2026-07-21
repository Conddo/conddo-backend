package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.TenantPaymentAccount;
import io.conddo.core.service.AdminKycService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Admin KYC review console at {@code /api/v1/admin/kyc}. SUPER_ADMIN only.
 * The review action flips {@code paymentsEnabled} for the tenant, which is
 * the master switch every downstream collection endpoint checks before
 * accepting live customer payments.
 */
@RestController
@RequestMapping("/api/v1/admin/kyc")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminKycController {

    private final AdminKycService service;

    public AdminKycController(AdminKycService service) {
        this.service = service;
    }

    @GetMapping("/pending")
    public ApiResponse<List<AdminAccountView>> pending() {
        return ApiResponse.ok(service.pendingReview().stream()
                .map(AdminAccountView::of).toList());
    }

    @GetMapping("/{tenantId}")
    public ApiResponse<AdminAccountView> get(@PathVariable UUID tenantId) {
        return ApiResponse.ok(AdminAccountView.of(service.get(tenantId)));
    }

    @PostMapping("/{tenantId}/approve")
    public ApiResponse<AdminAccountView> approve(@PathVariable UUID tenantId,
                                                 @AuthenticationPrincipal Jwt jwt) {
        UUID reviewerId = UUID.fromString(jwt.getSubject());
        return ApiResponse.ok(AdminAccountView.of(service.approve(tenantId, reviewerId)));
    }

    @PostMapping("/{tenantId}/reject")
    public ApiResponse<AdminAccountView> reject(@PathVariable UUID tenantId,
                                                @Valid @RequestBody RejectRequest req,
                                                @AuthenticationPrincipal Jwt jwt) {
        UUID reviewerId = UUID.fromString(jwt.getSubject());
        return ApiResponse.ok(AdminAccountView.of(service.reject(tenantId, reviewerId, req.reason())));
    }

    public record RejectRequest(@NotBlank String reason) {}

    /** Admin-facing view — includes reviewer + timestamps the tenant view hides. */
    public record AdminAccountView(
            UUID tenantId,
            String bankCode, String bankName,
            String accountNumber, String accountName,
            boolean accountVerified,
            String kycStatus,
            OffsetDateTime kycSubmittedAt,
            OffsetDateTime kycReviewedAt,
            UUID kycReviewedBy,
            String kycRejectionReason,
            String cacDocumentUrl, String directorIdUrl,
            String utilityBillUrl, String businessAddress,
            boolean paymentsEnabled
    ) {
        static AdminAccountView of(TenantPaymentAccount a) {
            return new AdminAccountView(
                    a.getTenantId(),
                    a.getBankCode(), a.getBankName(),
                    a.getAccountNumber(), a.getAccountName(),
                    a.getAccountVerifiedAt() != null,
                    a.getKycStatus(),
                    a.getKycSubmittedAt(),
                    a.getKycReviewedAt(),
                    a.getKycReviewedBy(),
                    a.getKycRejectionReason(),
                    a.getKycCacDocumentUrl(), a.getKycDirectorIdUrl(),
                    a.getKycUtilityBillUrl(), a.getKycBusinessAddress(),
                    a.isPaymentsEnabled()
            );
        }
    }
}
