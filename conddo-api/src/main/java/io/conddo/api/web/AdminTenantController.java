package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.TenantCreditAccount;
import io.conddo.core.domain.TenantSite;
import io.conddo.core.domain.User;
import io.conddo.core.service.AdminTenantService;
import io.conddo.core.service.AdminTenantService.InviteResult;
import io.conddo.core.service.AdminTenantService.TenantDetail;
import io.conddo.core.service.AdminTenantService.TenantSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * Admin tenant CRUD for {@code studio.getconddo.com}. Full CRUD-plus:
 * list, detail, create (with invite URL), reset-password bypass,
 * deactivate. Every endpoint is SUPER_ADMIN-only.
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminTenantController {

    private final AdminTenantService service;

    public AdminTenantController(AdminTenantService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<TenantRow>> list() {
        return ApiResponse.ok(service.listAll().stream()
                .map(TenantRow::of).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<TenantDetailRow> get(@PathVariable UUID id) {
        return ApiResponse.ok(TenantDetailRow.of(service.detail(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CreateTenantResponse>> create(
            @Valid @RequestBody CreateTenantRequest body) {
        InviteResult result = service.provisionForCustomer(
                body.businessName(), body.verticalId(), body.planId(),
                body.ownerEmail(), body.ownerFullName());
        return ResponseEntity
                .status(org.springframework.http.HttpStatus.CREATED)
                .body(ApiResponse.ok(CreateTenantResponse.of(result)));
    }

    @PostMapping("/{id}/reset-password")
    public ApiResponse<PasswordResetTriggeredResponse> triggerReset(@PathVariable UUID id) {
        boolean sent = service.triggerPasswordReset(id);
        return ApiResponse.ok(new PasswordResetTriggeredResponse(sent));
    }

    @PostMapping("/{id}/deactivate")
    public ApiResponse<TenantRow> deactivate(@PathVariable UUID id) {
        Tenant t = service.deactivate(id);
        // Re-fetch a fresh summary (with counts) so the FE row reflects
        // the new status without a second round trip.
        return ApiResponse.ok(TenantRow.of(service.detail(t.getId()).summary()));
    }

    // ----- wire records ----------------------------------------------------

    public record CreateTenantRequest(
            @NotBlank String businessName,
            @NotBlank String verticalId,
            @NotBlank String planId,
            @Email @NotBlank String ownerEmail,
            @NotBlank String ownerFullName) {}

    /** Compact row used by the tenants list panel. */
    public record TenantRow(
            UUID id, String slug, String name,
            String verticalId, String planId,
            String status, OffsetDateTime createdAt,
            String ownerEmail, String ownerFullName,
            long usersCount) {
        static TenantRow of(TenantSummary s) {
            return new TenantRow(s.id(), s.slug(), s.name(),
                    s.verticalId(), s.planId(),
                    s.status(), s.createdAt(),
                    s.ownerEmail(), s.ownerFullName(),
                    s.usersCount());
        }
    }

    public record TenantDetailRow(
            TenantRow summary,
            OwnerRow owner,
            long usersCount,
            long ordersCount,
            List<SiteRow> sites,
            CreditsRow credits) {
        static TenantDetailRow of(TenantDetail d) {
            return new TenantDetailRow(
                    TenantRow.of(d.summary()),
                    OwnerRow.of(d.owner()),
                    d.usersCount(), d.ordersCount(),
                    d.sites().stream().map(SiteRow::of).toList(),
                    CreditsRow.of(d.credits()));
        }
    }

    public record OwnerRow(UUID id, String email, String fullName, String phone,
                            boolean emailVerified, boolean phoneVerified,
                            OffsetDateTime lastLoginAt) {
        static OwnerRow of(User u) {
            if (u == null) return null;
            return new OwnerRow(u.getId(), u.getEmail(), u.getFullName(), u.getPhone(),
                    u.isEmailVerified(), u.isPhoneVerified(), u.getLastLoginAt());
        }
    }

    public record SiteRow(UUID id, String subdomain, String customDomain,
                          boolean qaApproved, boolean active,
                          OffsetDateTime createdAt) {
        static SiteRow of(TenantSite s) {
            return new SiteRow(s.getId(), s.getSubdomain(), s.getCustomDomain(),
                    s.isQaApproved(), s.isActive(), s.getCreatedAt());
        }
    }

    public record CreditsRow(String tier, int monthlyQuota, int creditsUsed,
                              int topupCredits, int reservedCredits, int available) {
        static CreditsRow of(TenantCreditAccount a) {
            if (a == null) return null;
            int available = a.getMonthlyQuota() + a.getTopupCredits()
                    - a.getCreditsUsed() - a.getReservedCredits();
            return new CreditsRow(a.getTier(), a.getMonthlyQuota(), a.getCreditsUsed(),
                    a.getTopupCredits(), a.getReservedCredits(), available);
        }
    }

    public record CreateTenantResponse(
            UUID tenantId, String slug, String name,
            String verticalId, String planId,
            String inviteUrl) {
        static CreateTenantResponse of(InviteResult r) {
            return new CreateTenantResponse(
                    r.tenant().getId(), r.tenant().getSlug(), r.tenant().getName(),
                    r.tenant().getVerticalId(), r.tenant().getPlanId(),
                    r.inviteUrl());
        }
    }

    public record PasswordResetTriggeredResponse(boolean sent) {}
}
